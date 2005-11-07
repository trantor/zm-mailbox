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

package com.zimbra.cs.store;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.zimbra.cs.db.DbPool;
import com.zimbra.cs.db.DbVolume;
import com.zimbra.cs.db.DbPool.Connection;
import com.zimbra.cs.redolog.op.CreateVolume;
import com.zimbra.cs.redolog.op.DeleteVolume;
import com.zimbra.cs.redolog.op.ModifyVolume;
import com.zimbra.cs.redolog.op.RedoableOp;
import com.zimbra.cs.redolog.op.SetCurrentVolume;
import com.zimbra.cs.service.ServiceException;
import com.zimbra.cs.util.Zimbra;

/**
 * @author jhahm
 */
public class Volume {

    public static final short ID_AUTO_INCREMENT = -1;
    public static final short ID_NONE           = -2;
    public static final short ID_MAX            = 255;

    public static final short TYPE_MESSAGE           =  1;
    public static final short TYPE_MESSAGE_SECONDARY =  2;
    public static final short TYPE_INDEX             = 10;

    private static final String SUBDIR_MESSAGE = "msg";
    private static final String SUBDIR_INDEX = "index";

    private static final String INCOMING_DIR = "incoming";

    private static final short DEFAULT_MBOX_GROUP_BITS = 8;
    private static final short DEFAULT_MBOX_BITS = 12;
    private static final short DEFAULT_FILE_GROUP_BITS = 8;
    private static final short DEFAULT_FILE_BITS = 12;

    // sVolumeMap, sCurrMsgVolume, sCurrSecondaryMsgVolume, and
    // sCurrIndexVolume are all synchronized on sVolumeGuard.
    private static final Object sVolumeGuard = new Object();
    private static Map sVolumeMap = new HashMap();
    private static Volume sCurrMsgVolume;
    private static Volume sCurrSecondaryMsgVolume;
    private static Volume sCurrIndexVolume;

    static {
        try {
            reloadVolumes();
        } catch (ServiceException e) {
            Zimbra.halt("Unable to load volumes info", e);
        }
    }

    public static void reloadVolumes() throws ServiceException {
        Connection conn = null;
        try {
            conn = DbPool.getConnection();
            Map volumes = DbVolume.getAll(conn);
            DbVolume.CurrentVolumes currVols = DbVolume.getCurrentVolumes(conn);
            if (currVols == null)
                throw VolumeServiceException.BAD_CURRVOL_CONFIG("Missing current volumes info from configuration");

            Volume currMsgVol = (Volume) volumes.get(new Short(currVols.msgVolId));
            if (currMsgVol == null)
                throw VolumeServiceException.BAD_CURRVOL_CONFIG("Unknown current message volume " + currVols.msgVolId);
            Volume currSecondaryMsgVol = null;
            if (currVols.secondaryMsgVolId != ID_NONE) {
                currSecondaryMsgVol = (Volume) volumes.get(new Short(currVols.secondaryMsgVolId));
                if (currSecondaryMsgVol == null)
                    throw VolumeServiceException.BAD_CURRVOL_CONFIG("Unknown current secondary message volume " + currVols.secondaryMsgVolId);
            }
            Volume currIndexVol = (Volume) volumes.get(new Short(currVols.indexVolId));
            if (currIndexVol == null)
                throw VolumeServiceException.BAD_CURRVOL_CONFIG("Unknown current index volume " + currVols.indexVolId);

            // All looks good.  Update current values.
            synchronized (sVolumeGuard) {
            	sVolumeMap.clear();
                sVolumeMap.putAll(volumes);
                sCurrMsgVolume = currMsgVol;
                sCurrSecondaryMsgVolume = currSecondaryMsgVol;
                sCurrIndexVolume = currIndexVol;
            }
        } finally {
            if (conn != null)
                DbPool.quietClose(conn);
        }
    }

    public static Volume create(short id, short type,
                                String name, String path,
                                short mboxGroupBits, short mboxBits,
                                short fileGroupBits, short fileBits,
                                boolean compressBlobs, long compressionThreshold)
    throws ServiceException {
        // TODO: For now we don't allow non-default values.
        mboxGroupBits = DEFAULT_MBOX_GROUP_BITS;
        mboxBits = DEFAULT_MBOX_BITS;
        fileGroupBits = DEFAULT_FILE_GROUP_BITS;
        fileBits = DEFAULT_FILE_BITS;

        CreateVolume redoRecorder = new CreateVolume(type, name, path,
                                                     mboxGroupBits, mboxBits,
                                                     fileGroupBits, fileBits,
                                                     compressBlobs, compressionThreshold);
        redoRecorder.start(System.currentTimeMillis());

        Short key = null;
        Volume vol = null;
        Connection conn = null;
        boolean success = false;
        try {
            if (name == null || name.length() < 1)
                throw VolumeServiceException.INVALID_REQUEST("Missing volume name");
            if (path == null || path.length() < 1)
                throw VolumeServiceException.INVALID_REQUEST("Missing volume path");

            if (!(new File(path)).exists())
                throw VolumeServiceException.NO_SUCH_PATH(path);

            conn = DbPool.getConnection();
            vol = DbVolume.create(conn, id, type, name, path,
                                  mboxGroupBits, mboxBits,
                                  fileGroupBits, fileBits,
                                  compressBlobs, compressionThreshold);
            success = true;
            redoRecorder.setId(vol.getId());
            redoRecorder.log();
            key = new Short(vol.getId());
            return vol;
        } finally {
            endTransaction(success, conn, redoRecorder);
            if (success) {
                synchronized (sVolumeGuard) {
                    sVolumeMap.put(key, vol);
                }
            }
        }
    }

    public static Volume update(short id, short type,
                                String name, String path,
                                short mboxGroupBits, short mboxBits,
                                short fileGroupBits, short fileBits,
                                boolean compressBlobs, long compressionThreshold)
    throws ServiceException {
        // TODO: For now we don't allow non-default values.
        mboxGroupBits = DEFAULT_MBOX_GROUP_BITS;
        mboxBits = DEFAULT_MBOX_BITS;
        fileGroupBits = DEFAULT_FILE_GROUP_BITS;
        fileBits = DEFAULT_FILE_BITS;

        // Don't allow changing type of a current volume.  The volume must be
        // first made non-current before its type can be changed.  A volume
        // can be made non-current when another volume is made current for
        // the volume type.
        Volume vol = getById(id);
        if (type != vol.getType()) {
            synchronized (sVolumeGuard) {
                if ((sCurrMsgVolume != null && id == sCurrMsgVolume.getId()) ||
                    (sCurrSecondaryMsgVolume != null && id == sCurrSecondaryMsgVolume.getId()) ||
                    (sCurrIndexVolume != null && id == sCurrIndexVolume.getId())) {
                    throw VolumeServiceException.CANNOT_CHANGE_TYPE_OF_CURRVOL(vol, type);
                }
            }
        }

        ModifyVolume redoRecorder = new ModifyVolume(id, type, name, path,
                                                     mboxGroupBits, mboxBits,
                                                     fileGroupBits, fileBits,
                                                     compressBlobs, compressionThreshold);
        redoRecorder.start(System.currentTimeMillis());

        Connection conn = null;
        boolean success = false;
        try {
            if (name == null || name.length() < 1)
                throw VolumeServiceException.INVALID_REQUEST("Missing volume name");
            if (path == null || path.length() < 1)
                throw VolumeServiceException.INVALID_REQUEST("Missing volume path");

            if (!(new File(path)).exists())
                throw VolumeServiceException.NO_SUCH_PATH(path);

            conn = DbPool.getConnection();
            vol = DbVolume.update(conn, id, type, name, path,
                                  mboxGroupBits, mboxBits,
                                  fileGroupBits, fileBits,
                                  compressBlobs, compressionThreshold);
            success = true;
            redoRecorder.log();
            return vol;
        } finally {
            endTransaction(success, conn, redoRecorder);
            if (success) {
                Short key = new Short(id);
                synchronized (sVolumeGuard) {
                    sVolumeMap.put(key, vol);
                }
            }
        }
    }

    /**
     * Remove the volume from the system.  Files on the volume being deleted
     * are not removed.
     * 
     * @param id volume ID
     * @return true if actual deletion occurred
     * @throws ServiceException
     */
    public static boolean delete(short id)
    throws ServiceException {
        DeleteVolume redoRecorder = new DeleteVolume(id);
        redoRecorder.start(System.currentTimeMillis());

        Volume vol = null;
        Short key = new Short(id);

        // Don't allow deleting the current message/index volume.
        synchronized (sVolumeGuard) {
            if (id == sCurrMsgVolume.getId())
                throw VolumeServiceException.CANNOT_DELETE_CURRVOL(id, "message");
            if (sCurrSecondaryMsgVolume != null &&
                id == sCurrSecondaryMsgVolume.getId())
                throw VolumeServiceException.CANNOT_DELETE_CURRVOL(id, "secondary message");
            if (id == sCurrIndexVolume.getId())
                throw VolumeServiceException.CANNOT_DELETE_CURRVOL(id, "index");

            // Remove from map now.
            vol = (Volume) sVolumeMap.remove(key);
        }

        Connection conn = null;
        boolean success = false;
        try {
            conn = DbPool.getConnection();
            boolean deleted = DbVolume.delete(conn, id);
            success = true;
            redoRecorder.log();
            return deleted;
        } finally {
            endTransaction(success, conn, redoRecorder);
            if (vol != null && !success) {
                // Ran into database error.  Undo map entry removal.
                synchronized (sVolumeGuard) {
                    sVolumeMap.put(key, vol);
                }
            }
        }
    }

    public static Volume getById(short id) throws ServiceException {
    	Volume v = null;
        Short key = new Short(id);
        synchronized (sVolumeGuard) {
        	v = (Volume) sVolumeMap.get(key);
        }
        if (v != null)
            return v;

        // Look up from db.
        Connection conn = null;
        try {
            conn = DbPool.getConnection();
            v = DbVolume.get(conn, id);
            if (v != null) {
            	synchronized (sVolumeGuard) {
            		sVolumeMap.put(key, v);
                }
                return v;
            } else {
                throw VolumeServiceException.NO_SUCH_VOLUME(id);
            }
        } finally {
            if (conn != null)
                DbPool.quietClose(conn);
        }
    }

    /**
     * Returns a new <code>List</code> of <code>Volume</code>s
     * that match the specified type.
     */
    public static List /*<Volume>*/ getByType(short type) {
        List volumes = getAll();
        Iterator i = volumes.iterator();
        while (i.hasNext()) {
            Volume v = (Volume) i.next();
            if (v.getType() != type) {
                i.remove();
            }
        }
        return volumes;
    }
    
    /**
     * Returns a new <code>List</code> that contains all <code>Volume</code>s.
     */
    public static List /*<Volume>*/ getAll() {
        List volumes;
        synchronized (sVolumeGuard) {
        	volumes = new ArrayList(sVolumeMap.values());
        }
        return volumes;
    }

    /**
     * Don't cache the returned Volume object.  Updates to volume information
     * by admin will create a different Volume object for the same volume ID.
     * @return
     */
    public static Volume getCurrentMessageVolume() {
    	synchronized (sVolumeGuard) {
    		return sCurrMsgVolume;
        }
    }

    /**
     * Don't cache the returned Volume object.  Updates to volume information
     * by admin will create a different Volume object for the same volume ID.
     * @return
     */
    public static Volume getCurrentSecondaryMessageVolume() {
        synchronized (sVolumeGuard) {
            return sCurrSecondaryMsgVolume;
        }
    }

    /**
     * Don't cache the returned Volume object.  Updates to volume information
     * by admin will create a different Volume object for the same volume ID.
     * @return
     */
    public static Volume getCurrentIndexVolume() {
        synchronized (sVolumeGuard) {
            return sCurrIndexVolume;
        }
    }

    /**
     * Set the current volume of given type.  Pass ID_NONE for id to unset.
     * @param volType
     * @param id
     * @throws ServiceException
     */
    public static void setCurrentVolume(short volType, short id)
    throws ServiceException {
        SetCurrentVolume redoRecorder = new SetCurrentVolume(volType, id);
        redoRecorder.start(System.currentTimeMillis());

        Volume vol = null;
        if (id != ID_NONE) {
            vol = getById(id);
            if (vol.getType() != volType)
                throw VolumeServiceException.WRONG_TYPE_CURRVOL(id, volType);
        }

        Connection conn = null;
        boolean success = false;
        try {
            conn = DbPool.getConnection();
            DbVolume.updateCurrentVolume(conn, volType, id);

            synchronized (sVolumeGuard) {
                if (volType == TYPE_MESSAGE)
                    sCurrMsgVolume = vol;
                else if (volType == TYPE_MESSAGE_SECONDARY)
                    sCurrSecondaryMsgVolume = vol;
                else
                    sCurrIndexVolume = vol;
            }

            success = true;
            redoRecorder.log();
        } finally {
            endTransaction(success, conn, redoRecorder);
        }
    }

    private static void endTransaction(boolean success,
                                       Connection conn,
                                       RedoableOp redoRecorder)
    throws ServiceException {
        if (conn != null) {
            if (success) {
                conn.commit();
                redoRecorder.commit();
            } else {
                conn.rollback();
                redoRecorder.abort();
            }
            DbPool.quietClose(conn);
        } else if (redoRecorder != null) {
            if (success)
                redoRecorder.commit();
            else
                redoRecorder.abort();
        }
    }



    private short mId;
    private short mType;
    private String mName;
    private String mRootPath;  // root of the volume
    private String mIncomingMsgDir;

    private short mMboxGroupBits;
    private short mMboxBits;
    private short mFileGroupBits;
    private short mFileBits;

    private int mMboxGroupBitMask;
    private int mFileGroupBitMask;
    private boolean mCompressBlobs;
    private long mCompressionThreshold;

    public Volume(short id, short type, String name, String rootPath,
                  short mboxGroupBits, short mboxBits,
                  short fileGroupBits, short fileBits,
                  boolean compressBlobs, long compressionThreshold) {
        mId = id;
        mType = type;
        mName = name;
        mRootPath = rootPath;
        mIncomingMsgDir = mRootPath + File.separator + INCOMING_DIR;

        mMboxGroupBits = mboxGroupBits;
        mMboxBits = mboxBits;
        mFileGroupBits = fileGroupBits;
        mFileBits = fileBits;

        long mask;
        mask = (long) Math.pow(2, mMboxGroupBits) - 1;
        mMboxGroupBitMask = (int) mask;
        mask = (long) Math.pow(2, mFileGroupBits) - 1;
        mFileGroupBitMask = (int) mask;
        
        mCompressBlobs = compressBlobs;
        mCompressionThreshold = compressionThreshold;
    }

    public short getId() { return mId; }
    public short getType() { return mType; }
    public String getName() { return mName; }
    public String getRootPath() { return mRootPath; }
    public String getIncomingMsgDir() { return mIncomingMsgDir; }
    public short getMboxGroupBits() { return mMboxGroupBits; }
    public short getMboxBits() { return mMboxBits; }
    public short getFileGroupBits() { return mFileGroupBits; }
    public short getFileBits() { return mFileBits; }
    public boolean getCompressBlobs() { return mCompressBlobs; }
    public long getCompressionThreshold() { return mCompressionThreshold; }

    private StringBuffer getMailboxDirStringBuffer(int mboxId, int type,
                                                   int extraCapacity) {
        String subdir = type == TYPE_INDEX ? SUBDIR_INDEX : SUBDIR_MESSAGE;

        StringBuffer sb;
        int capacity;

        long dir = mboxId >> mMboxBits;
        dir &= mMboxGroupBitMask;

        capacity = mRootPath.length() + 20 + extraCapacity;
        sb = new StringBuffer(capacity);
        sb.append(mRootPath)
            .append(File.separator).append(dir)
            .append(File.separator).append(mboxId)
            .append(File.separator).append(subdir);
        return sb;
    }

    public String getMailboxDir(int mboxId, int type) {
        return getMailboxDirStringBuffer(mboxId, type, 0).toString();
    }

    public String getBlobDir(int mboxId, int itemId) {
        long dir = itemId >> mFileBits;
        dir &= mFileGroupBitMask;

        StringBuffer sb = getMailboxDirStringBuffer(mboxId, TYPE_MESSAGE, 10);
        sb.append(File.separator).append(dir);
        return sb.toString();
    }

    public String toString() {
        StringBuffer sb = new StringBuffer("id=").append(mId);
        sb.append(", type=").append(mType);
        sb.append(", name=\"").append(mName);
        sb.append("\", rootpath=").append(mRootPath);
        sb.append(", mgbits=").append(mMboxGroupBits);
        sb.append(", mbits=").append(mMboxBits);
        sb.append(", fgbits=").append(mFileGroupBits);
        sb.append(", fbits=").append(mFileBits);
        sb.append(", compressBlobs=").append(mCompressBlobs);
        sb.append(", compressionThreshold=").append(mCompressionThreshold);
        return sb.toString();
    }
}
