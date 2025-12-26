package flunav.events;

public class UnknownEvent extends DomainEvent {
    public UnknownEvent() {
        super("UNKNOWN");
    }
}
