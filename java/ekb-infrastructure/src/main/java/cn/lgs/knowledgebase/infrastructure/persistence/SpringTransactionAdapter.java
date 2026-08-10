package cn.lgs.knowledgebase.infrastructure.persistence;

import cn.lgs.knowledgebase.application.port.Ports.TransactionPort;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

public final class SpringTransactionAdapter implements TransactionPort {
    private final TransactionTemplate template;

    public SpringTransactionAdapter(TransactionTemplate template) {
        if (template == null) throw new IllegalArgumentException("transaction template is required");
        this.template = template;
    }

    @Override
    public <T> T required(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
