package com.smartticket.phase3;

import com.smartticket.entity.Ticket;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AccessPolicy {
    private final Phase3Properties config;

    public AccessPolicy(Phase3Properties config) {
        this.config = config;
    }

    public void read(Ticket t) {
        var a = ActorContext.current();
        if (a == null) {
            if (config.isSecurityEnabled()) ActorContext.require();
            else return;
        }
        if (!a.admin()
                && !("SUBMITTER".equals(a.role()) && Objects.equals(t.getCreatorId(), a.id()))
                && !("HANDLER".equals(a.role()) && Objects.equals(t.getAssigneeId(), a.id())))
            throw ApiFailure.forbidden();
    }

    public void write(Ticket t) {
        read(t);
    }

    public void admin() {
        var a = ActorContext.current();
        if (a == null && !config.isSecurityEnabled()) return;
        if (!ActorContext.require().admin()) throw ApiFailure.forbidden();
    }

    public boolean validHandler(long id) {
        return config.getUsers().stream()
                .anyMatch(u -> u.getId() == id && "HANDLER".equals(u.getRole()));
    }
}
