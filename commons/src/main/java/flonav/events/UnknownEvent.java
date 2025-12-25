package flonav.events;

public class UnknownEvent extends DomainEvent {
    public UnknownEvent() {
        super("UNKNOWN");
    }
}
