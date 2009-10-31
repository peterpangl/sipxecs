/*
 *  Copyright (C) 2008 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 *  Contributors retain copyright to elements licensed under a Contributor Agreement.
 *  Licensed to the User under the LGPL license.
 *
 */
package org.sipfoundry.sipxbridge;

import gov.nist.javax.sip.SipStackExt;

import java.util.Collection;
import java.util.Iterator;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogState;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.TimeoutEvent;
import javax.sip.Transaction;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionState;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Hop;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.log4j.Logger;

/**
 * This is the JAIN-SIP listener that fields all request and response events
 * from the stack.
 * 
 * @author M. Ranganathan
 * 
 */
public class SipListenerImpl implements SipListener {

    private static Logger logger = Logger.getLogger(SipListenerImpl.class);

    /**
     * Handle a Dialog Terminated event. Cleans up all the resources associated
     * with a Dialog.
     */

    private static void handleAuthenticationChallenge(
            ResponseEvent responseEvent) throws Exception {

        SipProvider provider = (SipProvider) responseEvent.getSource();

        Dialog dialog = responseEvent.getDialog();
        /*
         * challenge from LAN side. Forward it to the WAN. 
         */
        if (provider == Gateway.getLanProvider()) {
            /*
             * If we do not handle LAN originated challenges, tear down the
             * call.
             * xx-6663: Forward authentication challenges.
             */
            if ( responseEvent.getClientTransaction() == null ) {
                logger.debug("Dropping response - no client transaction");
                return;
            }
            TransactionContext transactionContext = TransactionContext.get(responseEvent.getClientTransaction());
            ServerTransaction serverTransaction = transactionContext.getServerTransaction();
            Response newResponse = SipUtilities.createResponse(serverTransaction, responseEvent.getResponse().getStatusCode());
            SipUtilities.copyHeaders(responseEvent.getResponse(),newResponse);
            serverTransaction.sendResponse(newResponse);
            return;
        }

        Response response = responseEvent.getResponse();
        CSeqHeader cseqHeader = (CSeqHeader) response
                .getHeader(CSeqHeader.NAME);
        if ( responseEvent.getClientTransaction() == null || responseEvent
                .getClientTransaction().getApplicationData() == null  ) {
            logger.warn("Cannot process event : NullClientTransaction or NullTransactionContext");
            return;
        }
        /*
         * Note that we need to store a pointer in the TransactionContext
         * because REGISTER does not have a dialog.
         */
        ItspAccountInfo accountInfo = ((TransactionContext) responseEvent
                .getClientTransaction().getApplicationData())
                .getItspAccountInfo();

        String method = cseqHeader.getMethod();
        String callId = SipUtilities.getCallId(response);

        /*
         * This happens when we tried handling the challenge earlier.
         */

        if (accountInfo != null
                && (accountInfo.incrementFailureCount(callId) > 1 || accountInfo
                        .getPassword() == null)) {

            /*
             * Got a 4xx response. Increment the failure count for the account
             * and mark it as AUTHENTICATION_FAILED
             */
            accountInfo.setState(AccountState.AUTHENTICATION_FAILED);
            if (logger.isDebugEnabled()) {
                logger
                        .debug("SipListenerImpl: could not authenticate with server. method = "
                                + method);

            }
            accountInfo.removeFailureCounter(callId);
            if (responseEvent.getDialog() != null) {
                BackToBackUserAgent b2bua = DialogContext
                        .getBackToBackUserAgent(responseEvent.getDialog());
                logger
                        .debug("Cannot authenticate request -- tearing down call");
                if (b2bua != null) {
                    b2bua.tearDown(Gateway.SIPXBRIDGE_USER,
                            ReasonCode.AUTHENTICATION_FAILURE,
                            "Could not authenticate request");
                }
            }
            if (!accountInfo.isAlarmSent()) {
                Gateway.getAlarmClient().raiseAlarm(
                        "SIPX_BRIDGE_AUTHENTICATION_FAILED",
                        accountInfo.getSipDomain());
                accountInfo.setAlarmSent(true);
            }
            return;

        }

        ClientTransaction newClientTransaction = Gateway
                .getAuthenticationHelper().handleChallenge(response,
                        responseEvent.getClientTransaction(), provider,
                        method.equals(Request.REGISTER) ? 0 : -1);

        TransactionContext tad = (TransactionContext) responseEvent
                .getClientTransaction().getApplicationData();
        tad.setClientTransaction(newClientTransaction);

        if (dialog == null) {
            /*
             * Out of dialog challenge ( REGISTER ).
             */
            newClientTransaction.sendRequest();
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("SipListenerImpl : dialog = " + dialog);
            }

            BackToBackUserAgent b2bua = DialogContext
                    .getBackToBackUserAgent(responseEvent.getDialog());
            if (b2bua != null) {
                 DialogContext dialogApplicationData = (DialogContext) dialog.getApplicationData();

                 DialogContext newDialogApplicationData = DialogContext.attach(b2bua, newClientTransaction.getDialog(),
                        newClientTransaction, newClientTransaction
                        .getRequest());
                b2bua.addDialog(newDialogApplicationData);
             
                
                if ( newDialogApplicationData != dialogApplicationData ) {
                    b2bua.removeDialog(dialog);
                    newDialogApplicationData.setPeerDialog(dialogApplicationData
                            .getPeerDialog());
                    newClientTransaction.getDialog().setApplicationData(
                            newDialogApplicationData);
                    newDialogApplicationData.setItspInfo(dialogApplicationData.getItspInfo());
                    /*
                     * Hook the application data pointer of the previous guy in the
                     * chain at us.
                     */
                    DialogContext peerDialogApplicationData = (DialogContext) dialogApplicationData
                    .getPeerDialog().getApplicationData();
                    peerDialogApplicationData.setPeerDialog(newClientTransaction
                            .getDialog());
                    newDialogApplicationData.setRtpSession(dialogApplicationData
                            .getRtpSession());

                    if (logger.isDebugEnabled()) {
                        logger.debug("SipListenerImpl: New Dialog = "
                                + newClientTransaction.getDialog());
                    }
                    
                }
               

            }

            if (dialog.getState() == DialogState.CONFIRMED) {
                /*
                 * In-DIALOG challenge. Re-INVITE was challenged.
                 */
                ToHeader toHeader = (ToHeader) newClientTransaction
                        .getRequest().getHeader(ToHeader.NAME);
                if (toHeader.getTag() != null) {
                    /*
                     * This check should not be necessary.
                     */
                    dialog.sendRequest(newClientTransaction);
                  
                }

            } else {
                DialogContext dialogContext  = DialogContext.get(newClientTransaction.getDialog());
                b2bua.addDialog(dialogContext);  
                newClientTransaction.sendRequest();
            }
           
            DialogContext dialogContext  = DialogContext.get(newClientTransaction.getDialog());
            if ( !dialogContext.isSessionTimerStarted()) {
                dialogContext.startSessionTimer();
            }
            
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.SipListener#processDialogTerminated(javax.sip.DialogTerminatedEvent )
     */
    public void processDialogTerminated(DialogTerminatedEvent dte) {
        DialogContext dialogContext = DialogContext.get(dte.getDialog());
        if ( dialogContext != null ) {
            logger.debug("DialogTerminatedEvent:  dialog created at " + dialogContext.getCreationPointStackTrace());
            logger.debug("DialogTerminatedEvent: dialog inserted at " + dialogContext.getInsertionPointStackTrace());
            logger.debug("DialogCreated by request: " + dialogContext.getRequest());
            dialogContext.cancelSessionTimer();
            BackToBackUserAgent b2bua = dialogContext.getBackToBackUserAgent();
            if (b2bua != null) {
                b2bua.removeDialog(dte.getDialog());
            }
        }

    }

    public void processIOException(IOExceptionEvent ioex) {
        logger.error("Got an unexpected IOException " + ioex.getHost() + ":"
                + ioex.getPort() + "/" + ioex.getTransport());

    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.SipListener#processRequest(javax.sip.RequestEvent)
     */
    public void processRequest(RequestEvent requestEvent) {

        if (logger.isDebugEnabled()) {
            logger.debug("Gateway: got an incoming request "
                    + requestEvent.getRequest());
        }
        Request request = requestEvent.getRequest();
        String method = request.getMethod();
        SipProvider provider = (SipProvider) requestEvent.getSource();

        ViaHeader viaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);

        try {

            if (Gateway.getState() == GatewayState.STOPPING) {
                logger.debug("Gateway is stopping -- returning");
                return;
            } else if (Gateway.getState() == GatewayState.INITIALIZING) {
                logger.debug("Rejecting request -- gateway is initializing");

                Response response = ProtocolObjects.messageFactory
                        .createResponse(Response.SERVICE_UNAVAILABLE, request);
                response
                        .setReasonPhrase("Gateway is initializing -- try later");
                ServerTransaction st = requestEvent.getServerTransaction();
                if (st == null) {
                    st = provider.getNewServerTransaction(request);
                }

                st.sendResponse(response);
                return;

            } else if (provider == Gateway.getLanProvider()
                    && method.equals(Request.INVITE)
                    && ((viaHeader.getReceived() != null && !Gateway
                            .isAddressFromProxy(viaHeader.getReceived())) || !Gateway
                            .isAddressFromProxy(viaHeader.getHost()))) {
                /*
                 * Check to see that via header originated from proxy server.
                 */
                ServerTransaction st = requestEvent.getServerTransaction();
                if (st == null) {
                    st = provider.getNewServerTransaction(request);

                }

                Response forbidden = SipUtilities.createResponse(st,
                        Response.FORBIDDEN);
                forbidden
                        .setReasonPhrase("Request not issued from SIPX proxy server");
                st.sendResponse(forbidden);
                return;

            }

        } catch (TransactionAlreadyExistsException ex) {
            logger.error("transaction already exists", ex);
            return;
        } catch (Exception ex) {
            logger.error("Unexpected exception ", ex);
            throw new SipXbridgeException("Unexpected exceptione", ex);
        }

        if (method.equals(Request.INVITE) || method.equals(Request.ACK)
                || method.equals(Request.CANCEL) || method.equals(Request.BYE)
                || method.equals(Request.OPTIONS)
                || method.equals(Request.REFER) || method.equals(Request.PRACK)) {
            Gateway.getCallControlManager().processRequest(requestEvent);
        } else {
            try {
                Response response = ProtocolObjects.messageFactory
                        .createResponse(Response.METHOD_NOT_ALLOWED, request);
                ServerTransaction st = requestEvent.getServerTransaction();
                if (st == null) {
                    st = provider.getNewServerTransaction(request);
                }
                st.sendResponse(response);
            } catch (TransactionAlreadyExistsException ex) {
                logger.error("transaction already exists", ex);
            } catch (Exception ex) {
                logger.error("unexpected exception", ex);
                throw new SipXbridgeException("Unexpected exceptione", ex);
            }
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.sip.SipListener#processResponse(javax.sip.ResponseEvent)
     */

    public void processResponse(ResponseEvent responseEvent) {

        if (Gateway.getState() == GatewayState.STOPPING) {
            logger.debug("Gateway is stopping -- returning");
            return;
        }

        Response response = responseEvent.getResponse();
        CSeqHeader cseqHeader = (CSeqHeader) response
                .getHeader(CSeqHeader.NAME);

        String method = cseqHeader.getMethod();
        Dialog dialog = responseEvent.getDialog();

        try {

            if (dialog != null && dialog.getApplicationData() == null
                    && method.equals(Request.INVITE)) {
                /*
                 * if the tx does not exist but the dialog does exist then this
                 * is a forked response
                 */

                SipProvider provider = (SipProvider) responseEvent.getSource();
                logger.debug("Forked dialog response detected.");
                String callId = SipUtilities.getCallId(response);
                BackToBackUserAgent b2bua = Gateway.getBackToBackUserAgentFactory().getBackToBackUserAgent(callId);
                
                
                /*
                 * Kill off the dialog if we cannot find a dialog context.
                 */
                if (b2bua == null && response.getStatusCode() == Response.OK) {
                        Request ackRequest = dialog.createAck(cseqHeader
                                .getSeqNumber());
                        /* Cannot access the dialogContext here */
                        dialog.sendAck(ackRequest);
                        Request byeRequest = dialog.createRequest(Request.BYE);
                        ClientTransaction byeClientTransaction = provider
                        .getNewClientTransaction(byeRequest);
                        dialog.sendRequest(byeClientTransaction);
                        return;
                } 
                /*
                 * This is a forked response. We need to find the original call
                 * leg and retrieve the original RTP session from that call leg.
                 * TODO : Each call leg must get its own RTP bridge so this code
                 * needs to be rewritten. For now, they all share the same bridge.
                 */
                boolean found = false;
                String callLegId = SipUtilities.getCallLegId(response);
                for ( Dialog sipDialog : b2bua.dialogTable) {
                    if ( DialogContext.get(sipDialog).getCallLegId().equals(callLegId) && DialogContext.get(sipDialog).rtpSession != null ) {
                        DialogContext context = DialogContext.get(sipDialog);
                        Request request = context.getRequest();
                        DialogContext newContext = DialogContext.attach(b2bua, dialog,context.getDialogCreatingTransaction() , request);
                        newContext.setRtpSession(context.getRtpSession());
                        /*
                         * At this point we only do one half of the association
                         * with the peer dialog. When the ACK is sent, the other
                         * half of the association is established.
                         */
                        newContext.setPeerDialog(context.getPeerDialog());
                        dialog.setApplicationData(newContext);  
                        found = true;
                        break;
                    }
                }
                
                
                /*
                 * Could not find the original dialog context.
                 * This means the fork response came in too late. Send BYE
                 * to that leg.
                 */
                if ( dialog.getApplicationData() == null  ) {
                    logger.debug("callLegId = " + callLegId);
                    logger.debug("dialogTable = " + b2bua.dialogTable);
                    b2bua.tearDown(Gateway.SIPXBRIDGE_USER, ReasonCode.FORK_TIMED_OUT, "Fork timed out"); 
                    return;
                } 
            }

            /*
             * Handle proxy challenge.
             */
            if (response.getStatusCode() == Response.PROXY_AUTHENTICATION_REQUIRED
                    || response.getStatusCode() == Response.UNAUTHORIZED) {
                handleAuthenticationChallenge(responseEvent);
                return;
            }

            ItspAccountInfo accountInfo = null;

            if (responseEvent.getClientTransaction() != null
                    && ((TransactionContext) responseEvent
                            .getClientTransaction().getApplicationData()) != null) {
                accountInfo = ((TransactionContext) responseEvent.getClientTransaction()
                        .getApplicationData()).getItspAccountInfo();
            }

            String callId = SipUtilities.getCallId(response);

            /*
             * Garbage collect the failure counter if it exists.
             */
            if (accountInfo != null && response.getStatusCode() / 200 == 1) {
                accountInfo.removeFailureCounter(callId);
            }

            if (method.equals(Request.REGISTER)) {
                Gateway.getRegistrationManager().processResponse(responseEvent);
            } else if (method.equals(Request.INVITE)
                    || method.equals(Request.CANCEL)
                    || method.equals(Request.BYE)
                    || method.equals(Request.REFER)
                    || method.equals(Request.OPTIONS)) {
                Gateway.getCallControlManager().processResponse(responseEvent);
            } else {
                logger.warn("dropping response " + method);
            }

        } catch (Exception ex) {
            logger.error("Unexpected error processing response >>>> "
                    + response, ex);
            logger.error("cause = " + ex.getCause());
            if (dialog != null && DialogContext.get(dialog) != null) {
                DialogContext.get(dialog).getBackToBackUserAgent().tearDown();
            }

        }

    }

    /**
     * Remove state. Drop B2Bua structrue from our table so we will drop all
     * requests corresponding to this call in future.
     */

    public void processTimeout(TimeoutEvent timeoutEvent) {
        ClientTransaction ctx = timeoutEvent.getClientTransaction();
        try {
            if (ctx != null) {
                Request request = ctx.getRequest();

                if (request.getMethod().equals(Request.OPTIONS)) {
                    ClientTransaction clientTransaction = timeoutEvent
                            .getClientTransaction();
                    Dialog dialog = clientTransaction.getDialog();
                    BackToBackUserAgent b2bua = DialogContext.get(dialog)
                            .getBackToBackUserAgent();
                    b2bua.tearDown(Gateway.SIPXBRIDGE_USER,
                            ReasonCode.SESSION_TIMER_ERROR,
                            "OPTIONS Session timer timed out.");
                } else if (request.getMethod().equals(Request.REGISTER)) {
                    Gateway.getRegistrationManager().processTimeout(
                            timeoutEvent);
                } else if (request.getMethod().equals(Request.BYE)) {
                    ClientTransaction clientTransaction = timeoutEvent
                            .getClientTransaction();
                    Dialog dialog = clientTransaction.getDialog();
                    BackToBackUserAgent b2bua = DialogContext.get(dialog)
                            .getBackToBackUserAgent();
                    dialog.delete();
               } else if (request.getMethod().equals(Request.INVITE)) {
                    /*
                     * If this is a refer request -- grab the MOH Dialog and
                     * kill it. Otherwise we are stuck with the MOH dialog.
                     */
                    BackToBackUserAgent b2bua = DialogContext.get(
                            ctx.getDialog()).getBackToBackUserAgent();

                    TransactionContext transactionContext = TransactionContext
                            .get(ctx);
                    if (transactionContext.getOperation() == Operation.SEND_INVITE_TO_SIPX_PROXY) {               
                            b2bua.tearDown(Gateway.SIPXBRIDGE_USER,
                                    ReasonCode.CALL_SETUP_ERROR,
                                    "SipxProxy is down");
                    } else {
                        if (transactionContext.getOperation() == Operation.SEND_INVITE_TO_ITSP
                                || transactionContext.getOperation() == Operation.SPIRAL_BLIND_TRANSFER_INVITE_TO_ITSP) {
                            logger.debug("Timed sending request to ITSP -- trying alternate proxy");
                            if ( ctx.getState() != TransactionState.TERMINATED ) {
                                ctx.terminate();
                            }
                            /* Try another hop */
                            Collection<Hop> hops = transactionContext
                                    .getProxyAddresses();
                            if (hops == null || hops.size() == 0 ) {
                                b2bua.sendByeToMohServer();
                                TransactionContext txContext  = TransactionContext.get(ctx);
                                if ( txContext.getServerTransaction() != null
                                        && txContext.getServerTransaction().getState()
                                        != TransactionState.TERMINATED ) {
                                    Response errorResponse = SipUtilities.createResponse(txContext.getServerTransaction(), 
                                            Response.REQUEST_TIMEOUT);
                                    errorResponse.setReasonPhrase("ITSP Timed Out");
                                    SipUtilities.addSipFrag(errorResponse, "ITSP Domain : " 
                                            + txContext.getItspAccountInfo().getProxyDomain());
                                    txContext.getServerTransaction().sendResponse(errorResponse);
                                }
                            } else {
                                /*
                                 * We have another hop to try. OK send it to the 
                                 * other side.
                                 */
                                b2bua.resendInviteToItsp(timeoutEvent
                                        .getClientTransaction());
                            }

                        } else {
                            logger.debug("Timed out processing "
                                    + transactionContext.getOperation());
                            
                            b2bua.sendByeToMohServer();
                        }
                    }

                }
            }
        } catch (Exception ex) {
            logger.error("Error processing timeout event", ex);
        }

    }

    public void processTransactionTerminated(TransactionTerminatedEvent tte) {

        Transaction transaction = tte.getClientTransaction() != null ? tte
                .getClientTransaction() : tte.getServerTransaction();
        Dialog dialog = transaction.getDialog();
        Request request = transaction.getRequest();
        /*
         * When the INVITE tx terminates and the associated dialog state is
         * CONFIRMED, we increment the call count.
         */
        if (request.getMethod().equals(Request.INVITE)
                && dialog.getState() == DialogState.CONFIRMED
                && ((ToHeader) request.getHeader(ToHeader.NAME))
                        .getParameter("tag") == null) {
            TransactionContext txContext = TransactionContext.get(transaction);
            if (txContext != null
                    && (txContext.getOperation() == Operation.SEND_INVITE_TO_ITSP || txContext
                            .getOperation() == Operation.SEND_INVITE_TO_ITSP)) {
                Gateway.incrementCallCount();
            }
        }

    }

}
