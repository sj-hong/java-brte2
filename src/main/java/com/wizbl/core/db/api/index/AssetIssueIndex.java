package com.wizbl.core.db.api.index;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.disk.DiskIndex;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.core.capsule.AssetIssueCapsule;
import com.wizbl.core.db.common.WrappedByteArray;
import com.wizbl.core.db2.core.IBrte2ChainBase;
import com.wizbl.protos.Contract.AssetIssueContract;

import javax.annotation.PostConstruct;

import static com.googlecode.cqengine.query.QueryFactory.attribute;

@Component
@Slf4j
public class AssetIssueIndex extends AbstractIndex<AssetIssueCapsule, AssetIssueContract> {

  public static Attribute<WrappedByteArray, String> AssetIssue_OWNER_ADDRESS;
  public static SimpleAttribute<WrappedByteArray, String> AssetIssue_NAME;
  public static Attribute<WrappedByteArray, Long> AssetIssue_START;
  public static Attribute<WrappedByteArray, Long> AssetIssue_END;

  @Autowired
  public AssetIssueIndex(
      @Qualifier("assetIssueStore") final IBrte2ChainBase<AssetIssueCapsule> database) {
    super(database);
  }

  @PostConstruct
  public void init() {
    initIndex(DiskPersistence.onPrimaryKeyInFile(AssetIssue_NAME, indexPath));
    index.addIndex(DiskIndex.onAttribute(AssetIssue_OWNER_ADDRESS));
//    index.addIndex(DiskIndex.onAttribute(AssetIssue_NAME));
    index.addIndex(DiskIndex.onAttribute(AssetIssue_START));
    index.addIndex(DiskIndex.onAttribute(AssetIssue_END));
  }

  @Override
  protected void setAttribute() {
    AssetIssue_OWNER_ADDRESS =
        attribute(
            "assetIssue owner address",
            bytes -> {
              AssetIssueContract assetIssue = getObject(bytes);
              return ByteArray.toHexString(assetIssue.getOwnerAddress().toByteArray());
            });

    AssetIssue_NAME =
        attribute("assetIssue name", bytes -> {
          AssetIssueContract assetIssue = getObject(bytes);
          return assetIssue.getName().toStringUtf8();
        });

    AssetIssue_START =
        attribute("assetIssue start time", bytes -> {
          AssetIssueContract assetIssue = getObject(bytes);
          return assetIssue.getStartTime();
        });

    AssetIssue_END =
        attribute("assetIssue end time", bytes -> {
          AssetIssueContract assetIssue = getObject(bytes);
          return assetIssue.getEndTime();
        });

  }
}
