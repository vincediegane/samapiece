package sn.samapiece.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Active les taches {@code @Scheduled} (ex. worker des notifications de correspondance). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
