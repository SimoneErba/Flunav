package flunav.context;

import org.slf4j.MDC;

public final class UserContextHolder {
    private static final String SENDER_ID_MDC_KEY = "sender_id";
    private static final ThreadLocal<String> senderId = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void setSenderId(String id) {
        if (id == null) {
            senderId.remove();
            MDC.remove(SENDER_ID_MDC_KEY);
            return;
        }

        senderId.set(id);
        MDC.put(SENDER_ID_MDC_KEY, id);
    }

    public static String getSenderId() {
        return senderId.get();
    }

    public static SenderContext enterSenderContext(String id) {
        return new SenderContext(id);
    }

    public static void clear() {
        senderId.remove();
        MDC.remove(SENDER_ID_MDC_KEY);
    }

    public static final class SenderContext implements AutoCloseable {
        private final String previousSenderId;
        private final String previousMdcSenderId;

        private SenderContext(String id) {
            previousSenderId = senderId.get();
            previousMdcSenderId = MDC.get(SENDER_ID_MDC_KEY);
            setSenderId(id);
        }

        @Override
        public void close() {
            restoreThreadLocal(previousSenderId);
            restoreMdc(previousMdcSenderId);
        }
    }

    private static void restoreThreadLocal(String id) {
        if (id == null) {
            senderId.remove();
        } else {
            senderId.set(id);
        }
    }

    private static void restoreMdc(String id) {
        if (id == null) {
            MDC.remove(SENDER_ID_MDC_KEY);
        } else {
            MDC.put(SENDER_ID_MDC_KEY, id);
        }
    }
}
