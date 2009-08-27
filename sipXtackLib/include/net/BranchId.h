// 
// Copyright (C) 2007 Pingtel Corp., certain elements licensed under a Contributor Agreement.  
// Contributors retain copyright to elements licensed under a Contributor Agreement.
// Licensed to the User under the LGPL license.
// 
//////////////////////////////////////////////////////////////////////////////
#ifndef _BRANCHID_H_
#define _BRANCHID_H_

// SYSTEM INCLUDES

// APPLICATION INCLUDES
#include "utl/UtlString.h"
#include "utl/UtlSortedList.h"

// DEFINES
// CONSTANTS
// TYPEDEFS
// FORWARD DECLARATIONS
class Url;
class SipMessage;
class BranchIdTest;

/// Via header branch id generator/interpreter 
/**
 * A SIP branch id is a parameter on the Via header value that identifies the
 * transaction.  See RFC 3261 section 8.1.1.7.
 *
 * This class generates and interprets branch id values.  A sipXecs branch id value
 * has three parts: a fixed 'magic cookie' that acts as a recognizer (and
 * incorporates the RFC 3261 cookie), a value unique to each branch, and in
 * some cases a loop detection key value.
 *
 * A User Agent client transaction creates its BranchId by passing the message
 * to be sent; this transaction has no loop detection key value.
 *
 * A server transaction creates its BranchId by passing the branch parameter
 * value from the topmost Via in the received message (the parameter is a
 * UtlString).  
 *
 * If the message is being forked, then the parent transaction BranchId is
 * annotated with a loop detection key value by calling addFork with each
 * of the contacts to which the message is being forked.  After this annotation
 * has been done and before any outgoing fork is sent, the loopDetected method
 * should be called passing the parent message; if loopDetected returns non-zero, then
 * a loop has been detected and a loop detected error should be returned instead.
 * Some (hardcoded) parameters known to change during retargetting but which 
 * do not affect forking are removed before the key hash is calculated.
 *
 * @nosubgrouping
 */
class BranchId : protected UtlString
{
  public:

   // ================================================================
   /** @name                  Constructors
    *  
    * Construction of a BranchId varies depending on whether the transaction
    * it identifies is a server transaction (created by an incoming message
    * in a proxy) or a client transaction (representing an outgoing message).
    *
    * Before any BranchId constructor is used, the setSecret interface should 
    * be called to establish the server (or proxy cluster) unique secret
    * value for signing the hash values.  This signature enables the stack
    * to distinguish BranchId values that were generated by this instance or
    * a redundant instance from those generated by others.
    */
   ///@{

   /// Initialize the secret value used to sign values.
   static void setSecret(const UtlString& secret /**< used as input to sign the branch-id value.
                                                  * This should be chosen such that it:
                                                  * is hard for an attacker to guess (includes at
                                                  * least 32 bits of cryptographicaly random data).
                                                  * ideally, is the same in replicated proxies
                                                  */
                         );
   /**<
    * This must be called once at initialization time,
    * before any server transaction BranchId objects are created.
    *
    * It may be called after that, but doing so with a
    * new value will cause branch id values generated
    * with the old value to not be recognized as having
    * been generated by the same system.
    */

   /// constructor for a client transaction in a User Agent Client
   BranchId(const SipMessage& message   ///< the new message
            );

   /// constructor for a server transaction in a proxy
   BranchId(const UtlString& existingBranchIdValue /**< the branch parameter value from
                                                    *   the topmost Via of the inbound msg
                                                    */
            );
   /**<
    * Before using the branch id value to construct the children of the server,
    * transaction, addFork should be called with the contact URI for each of the forks.
    * This is used to construct the loop detection key, which is then copied
    * to each of the BranchIds for the child transactions.
    */

   /// Record a fork into the loop detection key for this branch.
   void addFork(const Url& contact);

   /// constructor for building a client transaction in a proxy
   BranchId(BranchId&         parentId, ///< the branchid of the server transaction
            const SipMessage& message   ///< the new message
            );
   /**<
    * This generates a new uniquepart, but uses the loop detection key
    * of the parent branch.  
    */

   ///@}
   
   // ================================================================
   /** @name                  Accessors
    *
    * These methods access the value to be placed in a Via header.
    */
   ///@{

   /// Accessor for the full string form of the value - used like UtlString::data()
   const char* data();

   /// Accessor for the full string form of the value.
   const UtlString& utlString();

   ///@}

   // ================================================================
   /** @name                  Inquiries
    *
    * 
    */
   ///@{

   /// Equality Operator
   bool equals(const BranchId& otherBranchId);
   
   /// Equality Operator
   bool equals(const UtlString& otherBranchId);
   
   /// Does this message contain a loop detection key equivalent to this branch?
   unsigned int loopDetected(const SipMessage& parent);
   /**<
    * MUST not be called until after all addFork calls (if any) have been done.
    * @returns the number of hops back (counted from 1) with which the loop was found.
    */

   /// Was an arbitrary branch id value produced by an RFC3261 stack?
   static bool isRFC3261(const UtlString& otherBranchId);

   /// Was the topmost branch id produced by this proxy (or a redundant peer)?
   static bool topViaIsMyBranch(const SipMessage& message);
   ///< @TODO needed for some response forwarding fixes, but not yet implemented.
      
   ///@}


   /// destructor
   virtual ~BranchId();

  protected:
   friend class BranchIdTest;
   
   /// The RFC 3261 recognition value - see section 8.1.1.7 Via.
   static const char* RFC3261_MAGIC_COOKIE;
   ///< to check for this value, use the isRFC3261 method.

   /// The (secret) unique value used to generate recognizers.
   static UtlString smIdSecret;

   /// A monotonically increasing value that ensures some input to unique part is always different.
   static unsigned int smCounter;
   
   /// Whether or not the value in the parent UtlString is valid.
   bool      mParentStringValid;
   UtlString mLoopDetectionKey;
   
   /// Contains the AddrSpec form URI values for all forks.
   UtlSortedList mForks;
   
  private:

   /// Calculate a value universally unique to this message.
   static void generateUniquePart(const SipMessage& message,
                                  size_t uniqueCounter,
                                  UtlString& uniqueValue
                                  );

   /// Parse a sipXecs branch id into its component parts.
   static bool parse(const UtlString& branchValue,   ///< input
                     size_t&          uniqueCounter, ///< output sequence value
                     UtlString&       uniqueValue,   ///< output
                     UtlString&       loopDetectKey  ///< output
                     );
   /**<
    * @returns true if this branch id was recognized as a sipXecs branch id
    *
    * This does not check whether or not the signature matches; it only
    * determines if this branch _looks_ like a branch id generated by _some_
    * sipXecs implementation using this class.
    */

   /// If needed, add the loop detect key to the unique value in the parent string.
   void generateFullValue();
   /**<
    * This MUST be called before any use of the parent value as a UtlString.
    */

   void generateUniquePart(const SipMessage& message);

   // @cond INCLUDENOCOPY
   /// There is no copy constructor.
   BranchId(const BranchId& nocopyconstructor);

   /// There is no assignment operator.
   BranchId& operator=(const BranchId& noassignmentoperator);
   // @endcond     
};

#endif // _BRANCHID_H_
