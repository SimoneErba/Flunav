package com.flunav.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.OrientDBService;

@SpringBootTest(properties = { "springwolf.enabled=false" })
class BackendApplicationTests {

	@MockBean
	private OrientDBService orientDBService;

	@MockBean
	private ClickHouseService clickhouseService;

	@Test
	void contextLoads() {
	}

}
