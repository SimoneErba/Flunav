package com.flunav.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = { "springwolf.enabled=false" })
class BackendApplicationTests extends BaseIntegrationTest {

	@Test
	void contextLoads() {
	}

}
