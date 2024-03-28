package org.tron.core.store.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.TronStoreWithRevoking;

@Slf4j(topic = "DB")
@Component
public class SyncBlockStore extends TronStoreWithRevoking<BlockCapsule> {

    @Autowired
    private SyncBlockStore(@Value("sync-block") String dbName) {
        super(dbName);
    }

}
