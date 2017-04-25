package org.example.tomcat.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

@SpringBootApplication
@EnableRedisHttpSession(redisNamespace = "sample-session")

public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}
}
