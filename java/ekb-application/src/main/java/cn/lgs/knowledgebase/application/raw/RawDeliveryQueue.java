package cn.lgs.knowledgebase.application.raw;

import java.util.List;

/** Delivery is at least once; claims/attempts and publication remain in the database. */
public interface RawDeliveryQueue {
  record Message(String id, String fileId) {}

  String append(String fileId);

  List<Message> read(int limit);

  /** Only messages whose DB deadline/state permits recovery may be claimed. */
  List<Message> recover(int limit);

  void acknowledge(String messageId);

  /** Scheduling contention only; does not confer task ownership or renew any stage deadline. */
  default boolean withRecoveryLease(Runnable recovery) {
    recovery.run();
    return true;
  }
}
