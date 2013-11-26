/**
 *
 *
 * Copyright (c) 2011 eZuce, Inc. All rights reserved.
 * Contributed to SIPfoundry under a Contributor Agreement
 *
 * This software is free software; you can redistribute it and/or modify it under
 * the terms of the Affero General Public License (AGPL) as published by the
 * Free Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 */
package org.sipfoundry.attendant;

import java.util.LinkedList;
import java.util.List;

import org.sipfoundry.sipxivr.ApplicationConfiguraton;

public class AttendantConfig extends ApplicationConfiguraton {
    private String m_id; // The ID of this attendant
    private String m_name; // The name of this attendant
    private String m_prompt; // The top level prompt
    private boolean m_liveAttendant;
    private int m_ringFor;
    private String m_liveTransferUrl;
    private boolean m_followUserFwd;
    private final List<AttendantMenuItem> m_menuItems = new LinkedList<AttendantMenuItem>();;

    public AttendantConfig() {
        super();
    }

    public String getId() {
        return m_id;
    }

    public void setId(String id) {
        m_id = id;
    }

    public String getName() {
        return m_name;
    }

    public void setName(String name) {
        m_name = name;
    }

    public String getPrompt() {
        return m_prompt;
    }

    public void setPrompt(String prompt) {
        m_prompt = prompt;
    }

    public void addMenuItem(AttendantMenuItem item) {
        m_menuItems.add(item);
    }

    public List<AttendantMenuItem> getMenuItems() {
        return m_menuItems;
    }

    public void setRingFor() {
        // TODO Auto-generated method stub

    }

    public int getRingFor() {
        return m_ringFor;
    }

    public void setRingFor(int ringFor) {
        m_ringFor = ringFor;
    }

    public String getLiveTransferUrl() {
        return m_liveTransferUrl;
    }

    public void setLiveTransferUrl(String liveTransferUrl) {
        m_liveTransferUrl = liveTransferUrl;
    }

    public boolean isFollowUserFwd() {
        return m_followUserFwd;
    }

    public void setFollowUserFwd(boolean followUserFwd) {
        m_followUserFwd = followUserFwd;
    }

    public boolean isLiveAttendant() {
        return m_liveAttendant;
    }

    public void setLiveAttendant(boolean liveAttendant) {
        m_liveAttendant = liveAttendant;
    }


}
