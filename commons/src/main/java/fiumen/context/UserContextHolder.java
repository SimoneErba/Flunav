package fiumen.context;

public class UserContextHolder {
    // ThreadLocal ensures that if 100 users request at the same time,
    // each thread sees its own specific ID.
    private static final ThreadLocal<String> senderId = new ThreadLocal<>();

    public static void setSenderId(String id) {
        senderId.set(id);
    }

    public static String getSenderId() {
        return senderId.get();
    }

    public static void clear() {
        senderId.remove();
    }
}