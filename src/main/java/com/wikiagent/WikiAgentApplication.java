package com.wikiagent;

import com.wikiagent.config.TaskProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties(TaskProperties.class)
public class WikiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(WikiAgentApplication.class, args);
    }
}
