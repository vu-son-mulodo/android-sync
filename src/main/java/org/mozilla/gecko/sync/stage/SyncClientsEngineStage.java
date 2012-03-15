/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.sync.stage;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.simple.JSONArray;
import org.mozilla.gecko.sync.CryptoRecord;
import org.mozilla.gecko.sync.GlobalSession;
import org.mozilla.gecko.sync.HTTPFailureException;
import org.mozilla.gecko.sync.Logger;
import org.mozilla.gecko.sync.NoCollectionKeysSetException;
import org.mozilla.gecko.sync.Utils;
import org.mozilla.gecko.sync.crypto.CryptoException;
import org.mozilla.gecko.sync.crypto.KeyBundle;
import org.mozilla.gecko.sync.delegates.ClientsDataDelegate;
import org.mozilla.gecko.sync.net.BaseResource;
import org.mozilla.gecko.sync.net.SyncStorageCollectionRequest;
import org.mozilla.gecko.sync.net.SyncStorageRecordRequest;
import org.mozilla.gecko.sync.net.SyncStorageResponse;
import org.mozilla.gecko.sync.net.WBOCollectionRequestDelegate;
import org.mozilla.gecko.sync.net.WBORequestDelegate;
import org.mozilla.gecko.sync.repositories.android.ClientsDatabaseAccessor;
import org.mozilla.gecko.sync.repositories.android.RepoUtils;
import org.mozilla.gecko.sync.repositories.domain.ClientRecord;
import org.mozilla.gecko.sync.repositories.domain.ClientRecordFactory;

import ch.boye.httpclientandroidlib.HttpStatus;

public class SyncClientsEngineStage implements GlobalSyncStage {
  public static final String LOG_TAG = "SyncClientsEngineStage";
  public static final String COLLECTION_NAME = "clients";
  public static final int CLIENTS_TTL_REFRESH = 604800000; // 7 days
  public static final int MAX_UPLOAD_FAILURE_COUNT = 5;

  protected GlobalSession session;
  protected final ClientRecordFactory factory = new ClientRecordFactory();
  protected ClientUploadDelegate clientUploadDelegate;
  protected ClientDownloadDelegate clientDownloadDelegate;
  protected ClientsDatabaseAccessor db;

  protected volatile boolean shouldWipe;
  protected volatile boolean commandsProcessedShouldUpload;
  protected final AtomicInteger uploadAttemptsCount = new AtomicInteger();

  /**
   * The following two delegates, ClientDownloadDelegate and ClientUploadDelegate
   * are both triggered in a chain, starting when execute() calls
   * downloadClientRecords().
   *
   * Client records are downloaded using a get() request. Upon success of the
   * get() request, the local client record is uploaded.
   *
   * @author Marina Samuel
   *
   */
  public class ClientDownloadDelegate extends WBOCollectionRequestDelegate {
    @Override
    public String credentials() {
      return session.credentials();
    }

    @Override
    public String ifUnmodifiedSince() {
      // TODO last client download time?
      return null;
    }

    @Override
    public void handleRequestSuccess(SyncStorageResponse response) {
      BaseResource.consumeEntity(response); // We don't need the response at all.
      try {
        // If we processed commands, then the last upload of our record was
        // not by us and we should update our local timestamp.
        if (commandsProcessedShouldUpload) {
          session.config.persistServerClientRecordTimestamp(response.normalizedWeaveTimestamp());
        }

        clientUploadDelegate = new ClientUploadDelegate();
        session.getClientsDelegate().setClientsCount(db.clientsCount());
        checkAndUpload();
      } finally {
        // Close the database to clear cached readableDatabase/writableDatabase
        // after we've completed our last transaction (db.store()).
        db.close();
      }
    }

    @Override
    public void handleRequestFailure(SyncStorageResponse response) {
      BaseResource.consumeEntity(response); // We don't need the response at all, and any exception handling shouldn't need the response body.
      try {
        Logger.info(LOG_TAG, "Client upload failed. Aborting sync.");
        session.abort(new HTTPFailureException(response), "Client download failed.");
      } finally {
        // Close the database upon failure.
        db.close();
      }
    }

    @Override
    public void handleRequestError(Exception ex) {
      try {
        Logger.info(LOG_TAG, "Client upload error. Aborting sync.");
        session.abort(ex, "Failure fetching client record.");
      } finally {
        // Close the database upon error.
        db.close();
      }
    }

    @Override
    public void handleWBO(CryptoRecord record) {
      ClientRecord r;
      try {
        r = (ClientRecord) factory.createRecord(record.decrypt());
        if (session.getClientsDelegate().isLocalGUID(r.guid)) {
          processCommands(r.commands);
        }
        RepoUtils.logClient(r);
      } catch (Exception e) {
        session.abort(e, "Exception handling client WBO.");
        return;
      }
      wipeAndStore(r);
    }

    @Override
    public KeyBundle keyBundle() {
      try {
        return session.keyForCollection(COLLECTION_NAME);
      } catch (NoCollectionKeysSetException e) {
        session.abort(e, "No collection keys set.");
        return null;
      }
    }
  }

  public class ClientUploadDelegate extends WBORequestDelegate {
    protected static final String LOG_TAG = "ClientUploadDelegate";

    @Override
    public String credentials() {
      return session.credentials();
    }

    @Override
    public String ifUnmodifiedSince() {
      Long timestampInMilliseconds = session.config.getPersistedServerClientRecordTimestamp();

      // It's the first upload so we don't care about X-If-Unmodified-Since.
      if (timestampInMilliseconds == 0) {
        return null;
      }

      return Utils.millisecondsToDecimalSecondsString(timestampInMilliseconds);
    }

    @Override
    public void handleRequestSuccess(SyncStorageResponse response) {
      commandsProcessedShouldUpload = false;
      uploadAttemptsCount.set(0);
      session.config.persistServerClientRecordTimestamp(response.normalizedWeaveTimestamp());

      BaseResource.consumeEntity(response);
      session.advance();
    }

    @Override
    public void handleRequestFailure(SyncStorageResponse response) {
      int statusCode = response.getStatusCode();

      // If upload failed because of `ifUnmodifiedSince` then there are new
      // commands uploaded to our record. We must download and process them first.
      if (!commandsProcessedShouldUpload ||
          statusCode == HttpStatus.SC_PRECONDITION_FAILED ||
          uploadAttemptsCount.incrementAndGet() > MAX_UPLOAD_FAILURE_COUNT) {
        Logger.debug(LOG_TAG, "Client upload failed. Aborting sync.");
        BaseResource.consumeEntity(response); // The exception thrown should need the response body.
        session.abort(new HTTPFailureException(response), "Client upload failed.");
        return;
      }

      // Preconditions:
      // commandsProcessedShouldUpload == true &&
      // statusCode != 412 &&
      // uploadAttemptCount < MAX_UPLOAD_FAILURE_COUNT
      checkAndUpload();
    }

    @Override
    public void handleRequestError(Exception ex) {
      Logger.info(LOG_TAG, "Client upload error. Aborting sync.");
      session.abort(ex, "Client upload failed.");
    }

    @Override
    public KeyBundle keyBundle() {
      try {
        return session.keyForCollection(COLLECTION_NAME);
      } catch (NoCollectionKeysSetException e) {
        session.abort(e, "No collection keys set.");
        return null;
      }
    }
  }

  @Override
  public void execute(GlobalSession session) throws NoSuchStageException {
    this.session = session;
    init();

    if (shouldDownload()) {
      downloadClientRecords();   // Will kick off upload, too…
    } else {
      // Upload if necessary.
    }
  }

  protected ClientRecord newLocalClientRecord(ClientsDataDelegate delegate) {
    final String ourGUID = delegate.getAccountGUID();
    final String ourName = delegate.getClientName();

    ClientRecord r = new ClientRecord(ourGUID);
    r.name = ourName;
    return r;    
  }

  protected void init() {
    db = new ClientsDatabaseAccessor(session.getContext());
  }

  // TODO: Bug 726055 - More considered handling of when to sync.
  protected boolean shouldDownload() {
    // Ask info/collections whether a download is needed.
    return true;
  }

  protected boolean shouldUpload() {
    if (commandsProcessedShouldUpload) {
      return true;
    }

    long now = System.currentTimeMillis();
    long lastUpload = session.config.getPersistedServerClientRecordTimestamp();   // Defaults to 0.
    long age = now - lastUpload;
    return age >= CLIENTS_TTL_REFRESH;
  }

  protected void processCommands(JSONArray commands) {
    if (commands == null ||
        commands.size() == 0) {
      return;
    }

    commandsProcessedShouldUpload = true;

    // TODO: Bug 715792 - Process commands here.
  }

  protected void checkAndUpload() {
    if (!shouldUpload()) {
      Logger.trace(LOG_TAG, "Not uploading client record.");
      session.advance();
      return;
    }

    // Generate CryptoRecord from ClientRecord to upload.
    String encryptionFailure = "Couldn't encrypt new client record.";
    ClientRecord localClient = newLocalClientRecord(session.getClientsDelegate());
    CryptoRecord cryptoRecord = localClient.getEnvelope();
    try {
      cryptoRecord.keyBundle = clientUploadDelegate.keyBundle();
      cryptoRecord.encrypt();
      this.wipeAndStore(localClient);
      this.uploadClientRecord(cryptoRecord);
    } catch (UnsupportedEncodingException e) {
      session.abort(e, encryptionFailure + " Unsupported encoding.");
    } catch (CryptoException e) {
      session.abort(e, encryptionFailure);
    }
  }

  protected void downloadClientRecords() {
    shouldWipe = true;
    clientDownloadDelegate = makeClientDownloadDelegate();

    try {
      URI getURI = session.config.collectionURI(COLLECTION_NAME, true);

      SyncStorageCollectionRequest request = new SyncStorageCollectionRequest(getURI);
      request.delegate = clientDownloadDelegate;

      Logger.trace(LOG_TAG, "Downloading client records.");
      request.get();
    } catch (URISyntaxException e) {
      session.abort(e, "Invalid URI.");
    }
  }

  protected void uploadClientRecord(CryptoRecord record) {
    try {
      URI putURI = session.config.wboURI(COLLECTION_NAME, record.guid);

      SyncStorageRecordRequest request = new SyncStorageRecordRequest(putURI);
      request.delegate = clientUploadDelegate;
      request.put(record);
    } catch (URISyntaxException e) {
      session.abort(e, "Invalid URI.");
    }
  }

  protected ClientDownloadDelegate makeClientDownloadDelegate() {
    return new ClientDownloadDelegate();
  }

  protected void wipeAndStore(ClientRecord record) {
    if (shouldWipe) {
      db.wipe();
      shouldWipe = false;
    }
    db.store(record);
  }
}
