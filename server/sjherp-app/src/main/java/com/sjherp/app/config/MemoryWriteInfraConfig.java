package com.sjherp.app.config;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.app.memory.MemoryService;
import com.sjherp.app.memory.MemoryWriteChannel;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.infra.persistence.memory.JdbcMemoryEntryRepository;

/** MySQL memory source-of-truth write path; vector infrastructure remains feature-gated. */
@Configuration(proxyBeanMethods = false)
public class MemoryWriteInfraConfig {
    @Bean
    @ConditionalOnMissingBean(MemoryEntryRepository.class)
    MemoryEntryRepository memoryWriteEntryRepository(JdbcTemplate jdbc) {
        return new JdbcMemoryEntryRepository(jdbc);
    }

    @Bean
    MemoryService memoryService(MemoryEntryRepository repository,
            DocumentNumberGenerator numberGenerator, ApplicationEventPublisher events) {
        return new MemoryService(repository, numberGenerator, events);
    }

    @Bean
    MemoryWriteChannel memoryWriteChannel(MemoryService memoryService) {
        return new MemoryWriteChannel(memoryService);
    }
}
