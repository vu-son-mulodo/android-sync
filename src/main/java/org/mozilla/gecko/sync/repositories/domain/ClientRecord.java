/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.sync.repositories.domain;

import org.mozilla.gecko.sync.ExtendedJSONObject;
import org.mozilla.gecko.sync.Utils;
import org.mozilla.gecko.sync.repositories.android.RepoUtils;
import org.mozilla.gecko.sync.setup.Constants;

public class ClientRecord extends Record {
  private static final String LOG_TAG = "ClientRecord";

  public static final String COLLECTION_NAME = "clients";

  public ClientRecord(String guid, String name, String type, String collection, long lastModified,
      boolean deleted) {
    super(guid, collection, lastModified, deleted);
    this.name = name;
    this.type = type;
  }

  public ClientRecord(String guid, String name, String type, String collection, long lastModified) {
    this(guid, name, type, collection, lastModified, false);
  }

  public ClientRecord(String guid, String name, String type, String collection) {
    this(guid, name, type, collection, 0, false);
  }

  public ClientRecord(String guid, String name, String type) {
    this(guid, name, type, COLLECTION_NAME, 0, false);
  }

  public ClientRecord(String guid, String name) {
    this(guid, name, Constants.CLIENT_TYPE, COLLECTION_NAME, 0, false);
  }

  public ClientRecord(String guid) {
    this(guid, Constants.DEFAULT_CLIENT_NAME, Constants.CLIENT_TYPE, COLLECTION_NAME, 0, false);
  }

  public ClientRecord() {
    this(Utils.generateGuid(), Constants.DEFAULT_CLIENT_NAME, Constants.CLIENT_TYPE, COLLECTION_NAME, 0, false);
  }

  public final String name;
  public final String type;

  @Override
  protected void initFromPayload(ExtendedJSONObject payload) {}

  @Override
  protected void populatePayload(ExtendedJSONObject payload) {
    putPayload(payload, "id",   this.guid);
    putPayload(payload, "name", this.name);
    putPayload(payload, "type", this.type);
  }

  public boolean equals(Object o) {
    if (!(o instanceof ClientRecord) || !super.equals(o)) {
      return false;
    }

    ClientRecord other = (ClientRecord) o;
    if (!RepoUtils.stringsEqual(other.name, this.name) ||
        !RepoUtils.stringsEqual(other.type, this.type)) {
      return false;
    }
    return true;
  }

  @Override
  public Record copyWithIDs(String guid, long androidID) {
    ClientRecord out = new ClientRecord(guid, this.name, this.type,
        this.collection, this.lastModified, this.deleted);
    out.androidID = androidID;
    out.sortIndex = this.sortIndex;

    return out;
  }

/*
Example record:

{id:"relf31w7B4F1",
 name:"marina_mac",
 type:"mobile"
 commands:[{"args":["bookmarks"],"command":"wipeEngine"},
           {"args":["forms"],"command":"wipeEngine"},
           {"args":["history"],"command":"wipeEngine"},
           {"args":["passwords"],"command":"wipeEngine"},
           {"args":["prefs"],"command":"wipeEngine"},
           {"args":["tabs"],"command":"wipeEngine"},
           {"args":["addons"],"command":"wipeEngine"}]}
*/
}