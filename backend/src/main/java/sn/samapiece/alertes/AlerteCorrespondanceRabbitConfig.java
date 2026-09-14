package sn.samapiece.alertes;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologie RabbitMQ du rapprochement alerte&lt;-&gt;piece : trois etages de backoff (TTL +
 * dead-letter-exchange) qui republient vers {@link #QUEUE_CONSUME}, une file de consommation
 * traitee par {@code AlerteCorrespondanceRetryListener}, et une file dead-letter finale sans
 * consumer applicatif. Calquee sur {@code SmsRabbitConfig}.
 *
 * <p>Ne declare volontairement pas de bean {@code MessageConverter} : {@code SmsRabbitConfig}
 * en declare deja un ({@code Jackson2JsonMessageConverter}) partage par tout le contexte Spring.
 * Un second bean du meme type casserait la resolution automatique par
 * {@code RabbitAutoConfiguration}.
 */
@Configuration
public class AlerteCorrespondanceRabbitConfig {

    public static final String EXCHANGE = "alerte.correspondance.exchange";
    public static final String QUEUE_RETRY_30S = "alerte.correspondance.retry.30s";
    public static final String QUEUE_RETRY_2M = "alerte.correspondance.retry.2m";
    public static final String QUEUE_RETRY_10M = "alerte.correspondance.retry.10m";
    public static final String QUEUE_CONSUME = "alerte.correspondance.consume";
    public static final String QUEUE_DEAD_LETTER = "alerte.correspondance.dead-letter";

    @Bean
    public DirectExchange alerteCorrespondanceExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue alerteCorrespondanceRetry30s(AlerteCorrespondanceProperties proprietes) {
        return QueueBuilder.durable(QUEUE_RETRY_30S)
                .ttl((int) proprietes.getRetryTtl30sMs())
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(QUEUE_CONSUME)
                .build();
    }

    @Bean
    public Queue alerteCorrespondanceRetry2m(AlerteCorrespondanceProperties proprietes) {
        return QueueBuilder.durable(QUEUE_RETRY_2M)
                .ttl((int) proprietes.getRetryTtl2mMs())
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(QUEUE_CONSUME)
                .build();
    }

    @Bean
    public Queue alerteCorrespondanceRetry10m(AlerteCorrespondanceProperties proprietes) {
        return QueueBuilder.durable(QUEUE_RETRY_10M)
                .ttl((int) proprietes.getRetryTtl10mMs())
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(QUEUE_CONSUME)
                .build();
    }

    @Bean
    public Queue alerteCorrespondanceConsume() {
        return QueueBuilder.durable(QUEUE_CONSUME).build();
    }

    @Bean
    public Queue alerteCorrespondanceDeadLetter() {
        return QueueBuilder.durable(QUEUE_DEAD_LETTER).build();
    }

    @Bean
    public Binding bindingAlerteCorrespondanceRetry30s(
            DirectExchange alerteCorrespondanceExchange, Queue alerteCorrespondanceRetry30s) {
        return BindingBuilder.bind(alerteCorrespondanceRetry30s).to(alerteCorrespondanceExchange).with(QUEUE_RETRY_30S);
    }

    @Bean
    public Binding bindingAlerteCorrespondanceRetry2m(
            DirectExchange alerteCorrespondanceExchange, Queue alerteCorrespondanceRetry2m) {
        return BindingBuilder.bind(alerteCorrespondanceRetry2m).to(alerteCorrespondanceExchange).with(QUEUE_RETRY_2M);
    }

    @Bean
    public Binding bindingAlerteCorrespondanceRetry10m(
            DirectExchange alerteCorrespondanceExchange, Queue alerteCorrespondanceRetry10m) {
        return BindingBuilder.bind(alerteCorrespondanceRetry10m).to(alerteCorrespondanceExchange).with(QUEUE_RETRY_10M);
    }

    @Bean
    public Binding bindingAlerteCorrespondanceConsume(
            DirectExchange alerteCorrespondanceExchange, Queue alerteCorrespondanceConsume) {
        return BindingBuilder.bind(alerteCorrespondanceConsume).to(alerteCorrespondanceExchange).with(QUEUE_CONSUME);
    }

    @Bean
    public Binding bindingAlerteCorrespondanceDeadLetter(
            DirectExchange alerteCorrespondanceExchange, Queue alerteCorrespondanceDeadLetter) {
        return BindingBuilder.bind(alerteCorrespondanceDeadLetter).to(alerteCorrespondanceExchange).with(QUEUE_DEAD_LETTER);
    }
}
