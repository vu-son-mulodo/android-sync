package org.mozilla.android.sync.test.helpers;

import java.util.ArrayList;
import java.util.Map;

import org.mozilla.gecko.sync.repositories.NullCursorException;
import org.mozilla.gecko.sync.repositories.android.ClientsDatabaseAccessor;
import org.mozilla.gecko.sync.repositories.domain.ClientRecord;

public class MockClientsDatabaseAccessor extends ClientsDatabaseAccessor {
  @Override
  public void store(ClientRecord record) {}

  @Override
  public void store(ArrayList<ClientRecord> records) {}

  @Override
  public ClientRecord fetch(String profileID) throws NullCursorException {
    return null;
  }

  @Override
  public Map<String, ClientRecord> fetchAll() throws NullCursorException {
    return null;
  }

  @Override
  public int numClients() {
    return 0;
  }

  @Override
  public void wipe() {}

  @Override
  public void close() {}
}