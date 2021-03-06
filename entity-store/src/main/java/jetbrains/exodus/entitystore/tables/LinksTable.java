/**
 * Copyright 2010 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore.tables;

import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.bindings.IntegerBinding;
import jetbrains.exodus.bindings.LongBinding;
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl;
import jetbrains.exodus.entitystore.PersistentStoreTransaction;
import jetbrains.exodus.env.*;
import org.jetbrains.annotations.NotNull;

public final class LinksTable extends TwoColumnTable {
    private final Store allLinksIndex;

    public LinksTable(@NotNull PersistentStoreTransaction txn, @NotNull String name, @NotNull StoreConfig config) {
        super(txn, name, config);
        PersistentEntityStoreImpl store = txn.getStore();
        final Transaction envTxn = txn.getEnvironmentTransaction();
        final Environment env = store.getEnvironment();
        allLinksIndex = env.openStore(name + ALL_IDX, StoreConfig.WITH_DUPLICATES_WITH_PREFIXING, envTxn);
        store.trackTableCreation(allLinksIndex, txn);
    }

    @Override
    public boolean put(@NotNull Transaction txn, @NotNull ByteIterable first, @NotNull ByteIterable second) {
        return super.put(txn, first, second); // TODO: replace inheritance with delegation
    }

    @Override
    public @NotNull Cursor getFirstIndexCursor(@NotNull Transaction txn) {
        return super.getFirstIndexCursor(txn);
    }

    @Override
    public @NotNull Cursor getSecondIndexCursor(@NotNull Transaction txn) {
        return super.getSecondIndexCursor(txn);
    }

    public boolean put(@NotNull Transaction txn,
                       final long localId,
                       @NotNull ByteIterable value,
                       boolean noOldValue,
                       final int linkId) {
        final PropertyKey linkKey = new PropertyKey(localId, linkId);
        boolean success = super.put(txn, PropertyKey.propertyKeyToEntry(linkKey), value);
        if (noOldValue) {
            success |= allLinksIndex.put(txn, IntegerBinding.intToCompressedEntry(linkId), LongBinding.longToCompressedEntry(localId));
        }
        return success;
    }

    @Override
    public boolean delete(@NotNull Transaction txn, @NotNull ByteIterable first, @NotNull ByteIterable second) {
        return super.delete(txn, first, second); // TODO: replace inheritance with delegation
    }

    public boolean delete(@NotNull Transaction txn,
                          final long localId,
                          @NotNull ByteIterable value,
                          boolean noNewValue,
                          final int linkId) {
        PropertyKey linkKey = new PropertyKey(localId, linkId);
        boolean success = super.delete(txn, PropertyKey.propertyKeyToEntry(linkKey), value);
        if (noNewValue) {
            success |= deleteAllIndex(txn, linkId, localId);
        }
        return success;
    }

    public boolean deleteAllIndex(@NotNull Transaction txn, int linkId, long localId) {
        return deletePair(allLinksIndex.openCursor(txn), IntegerBinding.intToCompressedEntry(linkId), LongBinding.longToCompressedEntry(localId));
    }

    public Store getAllLinksIndex() {
        return allLinksIndex;
    }

    @Override
    public boolean canBeCached() {
        return super.canBeCached() && !allLinksIndex.getConfig().temporaryEmpty;
    }

    private static boolean deletePair(@NotNull final Cursor c, @NotNull final ByteIterable key, @NotNull final ByteIterable value) {
        boolean result = c.getSearchBoth(key, value);
        if (result) {
            result = c.deleteCurrent();
        }
        c.close();
        return result;
    }
}
