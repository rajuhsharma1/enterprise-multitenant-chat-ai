package com.enterprise.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableScheduling
@EnableTransactionManagement(order = 0)
public class EnterpriseChatApplication {
  public static void main(String[] args) {
    SpringApplication.run(EnterpriseChatApplication.class, args);
  }
}
