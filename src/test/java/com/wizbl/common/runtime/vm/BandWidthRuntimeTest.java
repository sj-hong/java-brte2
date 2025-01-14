/*
 * java-brte2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-brte2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.wizbl.common.runtime.vm;

import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.runtime.Runtime;
import com.wizbl.common.runtime.RuntimeImpl;
import com.wizbl.common.runtime.TVMTestUtils;
import com.wizbl.common.runtime.vm.program.invoke.ProgramInvokeFactoryImpl;
import com.wizbl.common.storage.DepositImpl;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.ReceiptCapsule;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.db.TransactionTrace;
import com.wizbl.core.exception.*;
import com.wizbl.protos.Contract.CreateSmartContract;
import com.wizbl.protos.Contract.TriggerSmartContract;
import com.wizbl.protos.Protocol.AccountType;
import com.wizbl.protos.Protocol.Transaction;
import com.wizbl.protos.Protocol.Transaction.Contract;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;
import com.wizbl.protos.Protocol.Transaction.raw;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import org.junit.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.File;

/**
 * pragma solidity ^0.4.24;
 * <p>
 * contract ForI{
 * <p>
 * uint256 public balances;
 * <p>
 * function setCoin(uint receiver) public { for(uint i=0;i<receiver;i++){ balances = balances++; } }
 * }
 */
// TEST DELAYED => 수수료 정책 및 계산 방법에 대해서 추가 확인 후 테스트 진행 예정
public class BandWidthRuntimeTest {

    public static final long totalBalance = 1000_0000_000_000L;
    private static final String dbPath = "output_BandWidthRuntimeTest_test";
    private static final String dbDirectory = "db_BandWidthRuntimeTest_test";
    private static final String indexDirectory = "index_BandWidthRuntimeTest_test";
    private static final AnnotationConfigApplicationContext context;
    private static final String OwnerAddress = "tuzdVq2LYuiF9xxzVWtwVvg1CtAwvacCHX8iNx";
    private static final String TriggerOwnerAddress = "tuzdVq2LYuiF9xxzVWtwVvg1CtAwvacCHX8iNx";
    private static final String TriggerOwnerTwoAddress = "tuzdVq2LYuiF9xxzVWtwVvg1CtAwvacCHX8iNx";
    private static Manager dbManager;

    static {
        Args.setParam(
                new String[]{
                        "--output-directory", dbPath,
                        "--storage-db-directory", dbDirectory,
                        "--storage-index-directory", indexDirectory,
                        "-w"
                },
                Constant.TEST_CONF
        );
        context = new Brte2ApplicationContext(DefaultConfig.class);
    }

    /**
     * Init data.
     */
    @BeforeClass
    public static void init() {
        dbManager = context.getBean(Manager.class);
        //init energy
        dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(1526547838000L);
        dbManager.getDynamicPropertiesStore().saveTotalEnergyWeight(10_000_000L);

        dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(0);

        AccountCapsule accountCapsule = new AccountCapsule(ByteString.copyFrom("owner".getBytes()),
                ByteString.copyFrom(Wallet.decodeFromBase58Check(OwnerAddress)), AccountType.Normal,
                totalBalance);

        accountCapsule.setFrozenForEnergy(10_000_000L, 0L);
        dbManager.getAccountStore()
                .put(Wallet.decodeFromBase58Check(OwnerAddress), accountCapsule);

        AccountCapsule accountCapsule2 = new AccountCapsule(
                ByteString.copyFrom("triggerOwner".getBytes()),
                ByteString.copyFrom(Wallet.decodeFromBase58Check(TriggerOwnerAddress)), AccountType.Normal,
                totalBalance);

        accountCapsule2.setFrozenForEnergy(10_000_000L, 0L);
        dbManager.getAccountStore()
                .put(Wallet.decodeFromBase58Check(TriggerOwnerAddress), accountCapsule2);
        AccountCapsule accountCapsule3 = new AccountCapsule(
                ByteString.copyFrom("triggerOwnerAddress".getBytes()),
                ByteString.copyFrom(Wallet.decodeFromBase58Check(TriggerOwnerTwoAddress)),
                AccountType.Normal,
                totalBalance);
        accountCapsule3.setNetUsage(5000L);
        accountCapsule3.setLatestConsumeFreeTime(dbManager.getWitnessController().getHeadSlot());
        accountCapsule3.setFrozenForEnergy(10_000_000L, 0L);
        dbManager.getAccountStore()
                .put(Wallet.decodeFromBase58Check(TriggerOwnerTwoAddress), accountCapsule3);

        dbManager.getDynamicPropertiesStore()
                .saveLatestBlockHeaderTimestamp(System.currentTimeMillis() / 1000);
    }

    /**
     * destroy clear data of testing.
     */
    @AfterClass
    public static void destroy() {
        Args.clearParam();
        ApplicationFactory.create(context).shutdown();
        context.destroy();
        FileUtil.deleteDir(new File(dbPath));
    }

    @Test
    @Ignore // 수수료 정책 및 계산 방법 확인 후 테스트 진행 예정
    public void testSuccess() {
        try {
            byte[] contractAddress = createContract();
            AccountCapsule triggerOwner = dbManager.getAccountStore().get(Wallet.decodeFromBase58Check(TriggerOwnerAddress));
            long energy = triggerOwner.getEnergyUsage();
            TriggerSmartContract triggerContract = TVMTestUtils.createTriggerContract(contractAddress,
                    "setCoin(uint256)", "3", false,
                    0, Wallet.decodeFromBase58Check(TriggerOwnerAddress));
            Transaction transaction = Transaction.newBuilder().setRawData(raw.newBuilder().addContract(
                    Contract.newBuilder().setParameter(Any.pack(triggerContract))
                            .setType(ContractType.TriggerSmartContract)).setFeeLimit(1000000000)).build();
            TransactionCapsule trxCap = new TransactionCapsule(transaction);
            TransactionTrace trace = new TransactionTrace(trxCap, dbManager);
            dbManager.consumeBandwidth(trxCap, trace);
            BlockCapsule blockCapsule = null;
            DepositImpl deposit = DepositImpl.createRoot(dbManager);
            Runtime runtime = new RuntimeImpl(trace, blockCapsule, deposit, new ProgramInvokeFactoryImpl());
            trace.init(blockCapsule);
            trace.exec();
            trace.finalization();

            triggerOwner = dbManager.getAccountStore().get(Wallet.decodeFromBase58Check(TriggerOwnerAddress));
            energy = triggerOwner.getEnergyUsage();
            long balance = triggerOwner.getBalance();
            Assert.assertEquals(45706, trace.getReceipt().getEnergyUsageTotal());
            Assert.assertEquals(45706, energy);
            Assert.assertEquals(totalBalance, balance);
        } catch (Brte2Exception e) {
            Assert.assertNotNull(e);
        }
    }

    @Test
    @Ignore // 수수료 정책 및 계산 방법 확인 후 테스트 진행 예정
    public void testSuccessNoBandwidth() {
        try {
            byte[] contractAddress = createContract();
            TriggerSmartContract triggerContract = TVMTestUtils.createTriggerContract(contractAddress,
                    "setCoin(uint256)", "50", false,
                    0, Wallet.decodeFromBase58Check(TriggerOwnerTwoAddress));
            Transaction transaction = Transaction.newBuilder().setRawData(raw.newBuilder().addContract(
                    Contract.newBuilder().setParameter(Any.pack(triggerContract))
                            .setType(ContractType.TriggerSmartContract)).setFeeLimit(1000000000)).build();
            TransactionCapsule trxCap = new TransactionCapsule(transaction);
            TransactionTrace trace = new TransactionTrace(trxCap, dbManager);
            dbManager.consumeBandwidth(trxCap, trace);
            long bandWidth = trxCap.getSerializedSize() + Constant.MAX_RESULT_SIZE_IN_TX;
            BlockCapsule blockCapsule = null;
            DepositImpl deposit = DepositImpl.createRoot(dbManager);
            Runtime runtime = new RuntimeImpl(trace, blockCapsule, deposit,
                    new ProgramInvokeFactoryImpl());
            trace.init(blockCapsule);
            trace.exec();
            trace.finalization();

            AccountCapsule triggerOwnerTwo = dbManager.getAccountStore().get(Wallet.decodeFromBase58Check(TriggerOwnerTwoAddress));
            long balance = triggerOwnerTwo.getBalance();
            ReceiptCapsule receipt = trace.getReceipt();

            Assert.assertEquals(bandWidth, receipt.getNetUsage());
            Assert.assertEquals(522850, receipt.getEnergyUsageTotal());
            Assert.assertEquals(50000, receipt.getEnergyUsage());
            Assert.assertEquals(47285000, receipt.getEnergyFee());
            Assert.assertEquals(totalBalance - receipt.getEnergyFee(),
                    balance);
        } catch (Brte2Exception e) {
            Assert.assertNotNull(e);
        }
    }

    private byte[] createContract()
            throws ContractValidateException, AccountResourceInsufficientException, TooBigTransactionResultException, ContractExeException, VMIllegalException {
        AccountCapsule owner = dbManager.getAccountStore()
                .get(Wallet.decodeFromBase58Check(OwnerAddress));
        long energy = owner.getEnergyUsage();
        long balance = owner.getBalance();

        String contractName = "foriContract";
        String code = "608060405234801561001057600080fd5b50610105806100206000396000f3006080604052600436106049576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff1680637bb98a6814604e578063866edb47146076575b600080fd5b348015605957600080fd5b50606060a0565b6040518082815260200191505060405180910390f35b348015608157600080fd5b50609e6004803603810190808035906020019092919050505060a6565b005b60005481565b60008090505b8181101560d55760008081548092919060010191905055600081905550808060010191505060ac565b50505600a165627a7a72305820f4020a69fb8504d7db776726b19e5101c3216413d7ab8e91a11c4f55f772caed0029";
        String abi = "[{\"constant\":true,\"inputs\":[],\"name\":\"balances\",\"outputs\":[{\"name\":\"\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"receiver\",\"type\":\"uint256\"}],\"name\":\"setCoin\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"}]";
        CreateSmartContract smartContract = TVMTestUtils.createSmartContract(
                Wallet.decodeFromBase58Check(OwnerAddress), contractName, abi, code, 0, 100);
        Transaction transaction = Transaction.newBuilder().setRawData(raw.newBuilder().addContract(
                Contract.newBuilder().setParameter(Any.pack(smartContract))
                        .setType(ContractType.CreateSmartContract)).setFeeLimit(1000000000)).build();
        TransactionCapsule trxCap = new TransactionCapsule(transaction);
        TransactionTrace trace = new TransactionTrace(trxCap, dbManager);
        dbManager.consumeBandwidth(trxCap, trace);
        BlockCapsule blockCapsule = null;
        DepositImpl deposit = DepositImpl.createRoot(dbManager);
        Runtime runtime = new RuntimeImpl(trace, blockCapsule, deposit, new ProgramInvokeFactoryImpl());
        trace.init(blockCapsule);
        trace.exec();
        trace.finalization();
        owner = dbManager.getAccountStore()
                .get(Wallet.decodeFromBase58Check(OwnerAddress));
        energy = owner.getEnergyUsage() - energy;
        balance = balance - owner.getBalance();
        Assert.assertNull(runtime.getRuntimeError());
        Assert.assertEquals(52299, trace.getReceipt().getEnergyUsageTotal());
        Assert.assertEquals(50000, energy);
        Assert.assertEquals(229900, balance);
        Assert
                .assertEquals(52299 * Constant.SUN_PER_ENERGY, balance + energy * Constant.SUN_PER_ENERGY);
        Assert.assertNull(runtime.getRuntimeError());
        return runtime.getResult().getContractAddress();
    }
}