package com.smartticket.security;

import com.smartticket.exception.ApiFailure;

public final class ActorContext {
    public record Actor(long id, String role) {
        public boolean admin() {
            return "ADMIN".equals(role);
        }
    }

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private ActorContext() {}

    public static Actor current() {
        return CURRENT.get();
    }

    public static Actor require() {
        Actor a = current();
        if (a == null) throw new ApiFailure(401, 40100, "需要有效身份凭据");
        return a;
    }

    public static void set(Actor a) {
        CURRENT.set(a);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
