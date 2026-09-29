package com.iare.hackathon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"app.firebase.enabled=false", "app.supabase.enabled=false"})
class HackathonApplicationTests {

	@Test
	void contextLoads() {
	}

}
