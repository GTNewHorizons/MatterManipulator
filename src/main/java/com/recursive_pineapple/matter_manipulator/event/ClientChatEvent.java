package com.recursive_pineapple.matter_manipulator.event;

import cpw.mods.fml.common.eventhandler.Cancelable;
import cpw.mods.fml.common.eventhandler.Event;

public class ClientChatEvent extends Event {

    public String message;

    public ClientChatEvent(String message) {
        this.message = message;
    }

    @Cancelable
    public static final class Pre extends ClientChatEvent {

        public Pre(String message) {
            super(message);
        }
    }
}
