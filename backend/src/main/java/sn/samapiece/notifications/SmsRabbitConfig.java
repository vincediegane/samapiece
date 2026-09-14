package sn.samapiece.notifications;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologie RabbitMQ du retry technique de la passerelle SMS : trois etages de backoff
 * (TTL + dead-letter-exchange) qui republient vers {@link #QUEUE_CONSUME}, une file de
 * consommation traitee par {@link SmsRetryListener}, et une file dead-letter finale sans
 * consumer applicatif.
 */
@Configuration
public class SmsRabbitConfig {

    public static final String EXCHANGE = "sms.exchange";
    public static final String QUEUE_RETRY_30S = "sms.retry.30s";
    public static final String QUEUE_RETRY_2M = "sms.retry.2m";
    public static final String QUEUE_RETRY_10M = "sms.retry.10m";
    public static final String QUEUE_CONSUME = "sms.consume";
    public static final String QUEUE_DEAD_LETTER = "sms.dead-letter";

    @Bean
    public DirectExchange smsExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue smsRetry30s(SmsProperties proprietes) {
        return QueueBuilder.durable(QUEUE_RETRY_30S)
                .ttl((int) proprietes.getRetryTtl30sMs())
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(QUEUE_CONSUME)
                .build();
    }

    @Bean
    public Queue smsRetry2m(SmsProperties proprietes) {
        return QueueBuilder.durable(QUEUE_RETRY_2M)
                .ttl((int) proprietes.getRetryTtl2mMs())
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(QUEUE_CONSUME)
                .build();
    }

    @Bean
    public Queue smsRetry10m(SmsProperties proprietes) {
        return QueueBuilder.durable(QUEUE_RETRY_10M)
                .ttl((int) proprietes.getRetryTtl10mMs())
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(QUEUE_CONSUME)
                .build();
    }

    @Bean
    public Queue smsConsume() {
        return QueueBuilder.durable(QUEUE_CONSUME).build();
    }

    @Bean
    public Queue smsDeadLetter() {
        return QueueBuilder.durable(QUEUE_DEAD_LETTER).build();
    }

    @Bean
    public Binding bindingSmsRetry30s(DirectExchange smsExchange, Queue smsRetry30s) {
        return BindingBuilder.bind(smsRetry30s).to(smsExchange).with(QUEUE_RETRY_30S);
    }

    @Bean
    public Binding bindingSmsRetry2m(DirectExchange smsExchange, Queue smsRetry2m) {
        return BindingBuilder.bind(smsRetry2m).to(smsExchange).with(QUEUE_RETRY_2M);
    }

    @Bean
    public Binding bindingSmsRetry10m(DirectExchange smsExchange, Queue smsRetry10m) {
        return BindingBuilder.bind(smsRetry10m).to(smsExchange).with(QUEUE_RETRY_10M);
    }

    @Bean
    public Binding bindingSmsConsume(DirectExchange smsExchange, Queue smsConsume) {
        return BindingBuilder.bind(smsConsume).to(smsExchange).with(QUEUE_CONSUME);
    }

    @Bean
    public Binding bindingSmsDeadLetter(DirectExchange smsExchange, Queue smsDeadLetter) {
        return BindingBuilder.bind(smsDeadLetter).to(smsExchange).with(QUEUE_DEAD_LETTER);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
