package com.wizbl.core.net.node;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.overlay.discover.node.Node;
import com.wizbl.common.overlay.server.Channel;
import com.wizbl.common.overlay.server.ChannelManager;
import com.wizbl.common.overlay.server.SyncPool;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.common.utils.ReflectUtils;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.ByteArrayWrapper;
import com.wizbl.core.db.Manager;
import com.wizbl.core.net.message.TransactionMessage;
import com.wizbl.core.net.message.TransactionsMessage;
import com.wizbl.core.net.node.override.HandshakeHandlerTest;
import com.wizbl.core.net.node.override.PeerClientTest;
import com.wizbl.core.net.node.override.Brte2ChannelInitializerTest;
import com.wizbl.core.net.peer.PeerConnection;
import com.wizbl.core.services.RpcApiService;
import com.wizbl.core.services.WitnessService;
import com.wizbl.protos.Contract.TransferContract;
import com.wizbl.protos.Protocol;
import com.wizbl.protos.Protocol.Inventory.InventoryType;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.testng.collections.Lists;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

// TEST CLEAR
@Slf4j
public class HandleTransactionTest {

    private static final String dbPath = "output-HandleTransactionTest";
    private static final String dbDirectory = "db_HandleTransaction_test";
    private static final String indexDirectory = "index_HandleTransaction_test";
    private static Brte2ApplicationContext context;
    private static NodeImpl node;
    private static PeerClientTest peerClient;
    private static Application appT;
    private static Manager dbManager;
    private static HandshakeHandlerTest handshakeHandlerTest;
    private static boolean go = false;
    private RpcApiService rpcApiService;
    private ChannelManager channelManager;
    private SyncPool pool;
    private Node nodeEntity;

    private static Boolean deleteFolder(File index) {
        if (!index.isDirectory() || index.listFiles().length <= 0) {
            return index.delete();
        }
        for (File file : index.listFiles()) {
            if (null != file && !deleteFolder(file)) {
                return false;
            }
        }
        return index.delete();
    }

    @AfterClass
    public static void destroy() {
        Args.clearParam();
        Collection<PeerConnection> peerConnections = ReflectUtils.invokeMethod(node, "getActivePeer");
        for (PeerConnection peer : peerConnections) {
            peer.close();
        }
        handshakeHandlerTest.close();
        appT.shutdownServices();
        appT.shutdown();
        context.destroy();
        dbManager.getSession().reset();
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        FileUtil.deleteDir(new File(dbPath));
    }

    @Test
    public void testHandleTransactionMessage() throws Exception {

        TransferContract tc =
                TransferContract.newBuilder()
                        .setAmount(10)
                        .setOwnerAddress(ByteString.copyFromUtf8("aaa"))
                        .setToAddress(ByteString.copyFromUtf8("bbb"))
                        .build();

        TransactionCapsule trx = new TransactionCapsule(tc, ContractType.TransferContract);

        Protocol.Transaction transaction = trx.getInstance();

        TransactionMessage transactionMessage = new TransactionMessage(transaction);
        List list = Lists.newArrayList();
        list.add(transaction);
        TransactionsMessage transactionsMessage = new TransactionsMessage(list);

        PeerConnection peer = new PeerConnection();
        //没有向peer广播请求过交易信息
        peer.getAdvObjWeRequested().clear();
        peer.setSyncFlag(true);
        //nodeImpl.onMessage(peer, transactionMessage);
        //Assert.assertEquals(peer.getSyncFlag(), false);

        //向peer广播请求过交易信息
        peer.getAdvObjWeRequested().put(new Item(transactionMessage.getMessageId(), InventoryType.TRX), System.currentTimeMillis());
        peer.setSyncFlag(true);
        node.onMessage(peer, transactionsMessage);
        //Assert.assertEquals(peer.getAdvObjWeRequested().isEmpty(), true);
        //ConcurrentHashMap<Sha256Hash, InventoryType> advObjToSpread = ReflectUtils.getFieldValue(nodeImpl, "advObjToSpread");
        //Assert.assertEquals(advObjToSpread.contains(transactionMessage.getMessageId()), true);
    }

    @Before
    public void init() {
        nodeEntity = new Node(
                "enode://e437a4836b77ad9d9ffe73ee782ef2614e6d8370fcf62191a6e488276e23717147073a7ce0b444d485fff5a0c34c4577251a7a990cf80d8542e21b95aa8c5e6c@127.0.0.1:17891");

        new Thread(new Runnable() {
            @Override
            public void run() {
                logger.info("Full node running.");
                Args.setParam(
                        new String[]{
                                "--output-directory", dbPath,
                                "--storage-db-directory", dbDirectory,
                                "--storage-index-directory", indexDirectory
                        },
                        Constant.TEST_CONF
                );
                Args cfgArgs = Args.getInstance();
                cfgArgs.setNodeListenPort(17891);
                cfgArgs.setNodeDiscoveryEnable(false);
                cfgArgs.getSeedNode().getIpList().clear();
                cfgArgs.setNeedSyncCheck(false);
                cfgArgs.setNodeExternalIp("127.0.0.1");

                context = new Brte2ApplicationContext(DefaultConfig.class);

                if (cfgArgs.isHelp()) {
                    logger.info("Here is the help message.");
                    return;
                }
                appT = ApplicationFactory.create(context);
                rpcApiService = context.getBean(RpcApiService.class);
                appT.addService(rpcApiService);
                if (cfgArgs.isWitness()) {
                    appT.addService(new WitnessService(appT, context));
                }
//        appT.initServices(cfgArgs);
//        appT.startServices();
//        appT.startup();
                node = context.getBean(NodeImpl.class);
                peerClient = context.getBean(PeerClientTest.class);
                channelManager = context.getBean(ChannelManager.class);
                pool = context.getBean(SyncPool.class);
                dbManager = context.getBean(Manager.class);
                handshakeHandlerTest = context.getBean(HandshakeHandlerTest.class);
                handshakeHandlerTest.setNode(nodeEntity);
                NodeDelegate nodeDelegate = new NodeDelegateImpl(dbManager);
                node.setNodeDelegate(nodeDelegate);
                pool.init(node);
                prepare();
                rpcApiService.blockUntilShutdown();
            }
        }).start();
        int tryTimes = 0;
        while (tryTimes < 10 && (node == null || peerClient == null
                || channelManager == null || pool == null || !go)) {
            try {
                logger.info("node:{},peerClient:{},channelManager:{},pool:{},{}", node, peerClient,
                        channelManager, pool, go);
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                ++tryTimes;
            }
        }
    }

    private void prepare() {
        try {
            ExecutorService advertiseLoopThread = ReflectUtils.getFieldValue(node, "broadPool");
            advertiseLoopThread.shutdownNow();

            peerClient.prepare(nodeEntity.getHexId());

            ReflectUtils.setFieldValue(node, "isAdvertiseActive", false);
            ReflectUtils.setFieldValue(node, "isFetchActive", false);

            Brte2ChannelInitializerTest brte2ChannelInitializer = ReflectUtils
                    .getFieldValue(peerClient, "brte2ChannelInitializer");
            brte2ChannelInitializer.prepare();
            Channel channel = ReflectUtils.getFieldValue(brte2ChannelInitializer, "channel");
            ReflectUtils.setFieldValue(channel, "handshakeHandler", handshakeHandlerTest);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    peerClient.connect(nodeEntity.getHost(), nodeEntity.getPort(), nodeEntity.getHexId());
                }
            }).start();
            Thread.sleep(1000);
            Map<ByteArrayWrapper, Channel> activePeers = ReflectUtils
                    .getFieldValue(channelManager, "activePeers");
            int tryTimes = 0;
            while (MapUtils.isEmpty(activePeers) && ++tryTimes < 10) {
                Thread.sleep(1000);
            }
            go = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
