package com.wizbl.core.net.message;

import com.wizbl.protos.Protocol;

public class ItemNotFound extends Brte2Message {

  private com.wizbl.protos.Protocol.Items notFound;

  /**
   * means can not find this block or trx.
   */
  public ItemNotFound() {
    Protocol.Items.Builder itemsBuilder = Protocol.Items.newBuilder();
    itemsBuilder.setType(Protocol.Items.ItemType.ERR);
    notFound = itemsBuilder.build();
    this.type = MessageTypes.ITEM_NOT_FOUND.asByte();
    this.data = notFound.toByteArray();
  }

  @Override
  public String toString() {
    return "item not found";
  }

  @Override
  public Class<?> getAnswerMessage() {
    return null;
  }

}
