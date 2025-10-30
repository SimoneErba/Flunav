package com.fiumen.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.fiumen.backend.services.ClickHouseService;
import com.fiumen.backend.services.OrientDBService;

@SpringBootTest
class BackendApplicationTests {

	@MockBean
    private OrientDBService orientDBService; 

	@MockBean
    private ClickHouseService clickhouseService; 

	@Test
	void contextLoads() {
	}

}
