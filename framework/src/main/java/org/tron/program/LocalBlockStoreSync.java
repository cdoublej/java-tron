package org.tron.program;


import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.Metrics;
import org.tron.core.ChainBaseManager;
import org.tron.core.Constant;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.services.RpcApiService;
import org.tron.core.services.http.solidity.SolidityNodeHttpApiService;
import org.tron.core.store.sync.SyncBlockIndexStore;
import org.tron.core.store.sync.SyncBlockStore;
import org.tron.core.store.sync.SyncDynamicPropertiesStore;
import org.tron.protos.Protocol.Block;

@Slf4j(topic = "app")
public class LocalBlockStoreSync {

    private Manager dbManager;

    private ChainBaseManager chainBaseManager;

    private SyncBlockStore syncBlockStore;

    private SyncBlockIndexStore syncBlockIndexStore;

    private SyncDynamicPropertiesStore syncDynamicPropertiesStore;

    private AtomicLong ID = new AtomicLong();

    private AtomicLong syncTargetBlockNum = new AtomicLong();

    private LinkedBlockingDeque<Block> blockQueue = new LinkedBlockingDeque<>(100);

    private int exceptionSleepTime = 1000;

    private volatile boolean flag = true;

    public LocalBlockStoreSync(Manager dbManager) {
        this.dbManager = dbManager;
        this.chainBaseManager = dbManager.getChainBaseManager();
        this.syncBlockStore = dbManager.getChainBaseManager().getSyncBlockStore();
        this.syncBlockIndexStore = dbManager.getChainBaseManager().getSyncBlockIndexStore();
        this.syncDynamicPropertiesStore = dbManager.getChainBaseManager().getSyncDynamicPropertiesStore();

        ID.set(chainBaseManager.getDynamicPropertiesStore().getLatestLocalSyncedBlockNum());
        syncTargetBlockNum.set(getSyncTargetBlockNum());
    }

    /**
     * Start the Local block store sync process.
     */
    public static void main(String[] args) {
        logger.info("Local block store sync is running.");
        Args.setParam(args, Constant.TESTNET_CONF);
        CommonParameter parameter = CommonParameter.getInstance();

        logger.info("index switch is {}",
                BooleanUtils.toStringOnOff(BooleanUtils
                        .toBoolean(parameter.getStorage().getIndexSwitch())));

        parameter.setSolidityNode(true);

        TronApplicationContext context = new TronApplicationContext(DefaultConfig.class);
        context.registerShutdownHook();

        if (parameter.isHelp()) {
            logger.info("Here is the help message.");
            return;
        }
        // init metrics first
        Metrics.init();

        Application appT = ApplicationFactory.create(context);
        RpcApiService rpcApiService = context.getBean(RpcApiService.class);
        appT.addService(rpcApiService);
        //http
        SolidityNodeHttpApiService httpApiService = context.getBean(SolidityNodeHttpApiService.class);
        if (CommonParameter.getInstance().solidityNodeHttpEnable) {
            appT.addService(httpApiService);
        }

        appT.startup();
        appT.blockUntilShutdown();

        LocalBlockStoreSync sync = new LocalBlockStoreSync(appT.getDbManager());
        sync.start();
    }

    private void start() {
        try {
            new Thread(this::getBlock).start();
            new Thread(this::processBlock).start();
            logger.info("Success to start local store sync, ID: {}, targetSyncBlockNum: {}.", ID.get(),
                    syncTargetBlockNum);
        } catch (Exception e) {
            logger.error("Failed to start local store sync.", e);
            System.exit(0);
        }
    }

    private void getBlock() {
        long blockNum = ID.incrementAndGet();
        while (flag) {
            try {
                if (blockNum > syncTargetBlockNum.get()) {
                    logger.info("Finished local_block_store_sync get block with next block {}", blockNum);
                    break;
                }
                Block block = getBlockByNum(blockNum);
                blockQueue.put(block);
                blockNum = ID.incrementAndGet();
            } catch (Exception e) {
                logger.error("Failed to get block {}, reason: {}.", blockNum, e.getMessage());
                sleep(exceptionSleepTime);
            }
        }
    }

    private void processBlock() {
        while (flag) {
            try {
                Block block = blockQueue.take();
                loopProcessBlock(block);
            } catch (Exception e) {
                logger.error(e.getMessage());
                sleep(exceptionSleepTime);
            }
        }
    }

    private void loopProcessBlock(Block block) {
        while (flag) {
            long blockNum = block.getBlockHeader().getRawData().getNumber();
            try {
                dbManager.pushVerifiedBlock(new BlockCapsule(block));
                chainBaseManager.getDynamicPropertiesStore().saveLatestLocalSyncedBlockNum(blockNum);
                logger.info("Success to process block: {}, blockQueueSize: {}.", blockNum, blockQueue.size());
                return;
            } catch (Exception e) {
                logger.error("Failed to process block {}.", new BlockCapsule(block), e);
                sleep(exceptionSleepTime);
            }
        }
    }

    private Block getBlockByNum(long blockNum) throws BadItemException, ItemNotFoundException {
        return syncBlockStore.get(syncBlockIndexStore.get(blockNum).getBytes()).getInstance();
    }

    private long getSyncTargetBlockNum() {
        return syncDynamicPropertiesStore.getLatestBlockHeaderNumber();
    }

    public void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (Exception e1) {
            logger.error(e1.getMessage());
        }
    }

}
