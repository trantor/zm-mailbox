/*
 * ***** BEGIN LICENSE BLOCK *****
 * Version: ZPL 1.1
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.1 ("License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.zimbra.com/license
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 * 
 * The Original Code is: Zimbra Collaboration Suite.
 * 
 * The Initial Developer of the Original Code is Zimbra, Inc.
 * Portions created by Zimbra are Copyright (C) 2005 Zimbra, Inc.
 * All Rights Reserved.
 * 
 * Contributor(s): 
 * 
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.cs.redolog.op;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.zimbra.cs.store.Volume;
import com.zimbra.cs.store.VolumeServiceException;

public class DeleteVolume extends RedoableOp {

    private short mId;

    public DeleteVolume() {
    }

    public DeleteVolume(short id) {
        mId = id;
    }

    public int getOpCode() {
        return OP_DELETE_VOLUME;
    }

    protected String getPrintableData() {
        StringBuffer sb = new StringBuffer("id=").append(mId);
        return sb.toString();
    }

    protected void serializeData(DataOutput out) throws IOException {
        out.writeShort(mId);
    }

    protected void deserializeData(DataInput in) throws IOException {
        mId = in.readShort();
    }

    public void redo() throws Exception {
        Volume vol = null;
        try {
            vol = Volume.getById(mId);
            Volume.delete(mId);
        } catch (VolumeServiceException e) {
            if (e.getCode() != VolumeServiceException.NO_SUCH_VOLUME)
                throw e;
        }
    }
}
