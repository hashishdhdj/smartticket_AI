package com.smartticket.reception;

import com.smartticket.agent.AgentService;
import com.smartticket.governance.ApplicationMetrics;
import com.smartticket.governance.BudgetLedger;
import com.smartticket.security.ActorContext;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReceptionWorker {
    private final ReceptionRepository repo;
    private final ReceptionProperties config;
    private final AgentService agent;
    private final BudgetLedger budget;
    private final ApplicationMetrics metrics;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r -> {
        var thread=new Thread(r,"ticket-reception");thread.setDaemon(true);return thread;
    });

    public ReceptionWorker(ReceptionRepository repo,ReceptionProperties config,AgentService agent,
            BudgetLedger budget,ApplicationMetrics metrics) {
        this.repo=repo;this.config=config;this.agent=agent;this.budget=budget;this.metrics=metrics;
    }

    @Scheduled(fixedDelayString="${smartticket.reception.scheduler-delay-ms:2000}")
    public void tick() {
        if(!config.isEnabled() || !config.isAsyncEnabled() || !busy.compareAndSet(false,true))return;
        try {
            executor.execute(() -> {try {runOnce();} catch(Exception failure) {
                metrics.count("reception","scheduler_retry");
            } finally {busy.set(false);} });
        } catch(RejectedExecutionException stopped) {busy.set(false);}
    }

    /** Synchronous entry point for deterministic middleware tests; scheduling stays nonblocking. */
    public boolean runOnce() {
        if(!config.isEnabled())return false;
        var claimed=repo.claim();if(claimed.isEmpty())return false;
        var job=claimed.get(); var previous=ActorContext.current();
        // Least privilege: a creator who is an administrator does not grant the background Agent admin powers.
        ActorContext.set(new ActorContext.Actor(job.owner(),"SUBMITTER"));
        try {
            budget.request(job.owner());
            var result=agent.runReception(job.prompt(),job.ticketId());
            metrics.count("reception",repo.finish(job,result)?"committed":"stale_discarded");
        } catch(Exception failure) {
            repo.failed(job);metrics.count("reception","failed");
        } finally {
            if(previous==null)ActorContext.clear();else ActorContext.set(previous);
        }
        return true;
    }

    @PreDestroy public void close() {executor.shutdownNow();}
}
