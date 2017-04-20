/*
 * This file is part of ARSnova Backend.
 * Copyright (C) 2012-2017 The ARSnova Team
 *
 * ARSnova Backend is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ARSnova Backend is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.thm.arsnova.controller;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class StatisticsControllerTest extends AbstractControllerTest {

	@Autowired
	private StatisticsController statisticsController;

	private MockMvc mockMvc;

	@Autowired
	private WebApplicationContext webApplicationContext;

	@Before
	public void setup() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
	}

	@Test
	public void testShouldGetCurrentOnlineUsers() throws Exception {
		mockMvc.perform(get("/statistics/activeusercount").accept(MediaType.TEXT_PLAIN))
		.andExpect(status().isOk())
		.andExpect(content().contentTypeCompatibleWith("text/plain"));
	}

	@Test
	public void testShouldSendXDeprecatedApiForGetCurrentOnlineUsers() throws Exception {
		mockMvc.perform(get("/statistics/activeusercount").accept(MediaType.TEXT_PLAIN))
		.andExpect(status().isOk())
		.andExpect(content().contentTypeCompatibleWith("text/plain"))
		.andExpect(header().string(AbstractController.X_DEPRECATED_API,"1"));
	}

	@Test
	public void testShouldGetSessionCount() throws Exception {
		mockMvc.perform(get("/statistics/sessioncount").accept(MediaType.TEXT_PLAIN))
		.andExpect(status().isOk())
		.andExpect(content().contentTypeCompatibleWith("text/plain"))
		.andExpect(content().string(Matchers.greaterThanOrEqualTo("0")));
	}

	@Test
	public void testShouldSendXDeprecatedApiForGetSessionCount() throws Exception {
		mockMvc.perform(get("/statistics/sessioncount").accept(MediaType.TEXT_PLAIN))
		.andExpect(status().isOk())
		.andExpect(content().contentTypeCompatibleWith("text/plain"))
		.andExpect(header().string(AbstractController.X_DEPRECATED_API,"1"));
	}

	@Test
	public void testShouldGetStatistics() throws Exception {
		mockMvc.perform(get("/statistics").accept(MediaType.APPLICATION_JSON))
		.andExpect(status().isOk())
		.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
		.andExpect(jsonPath("$.answers").exists())
		.andExpect(jsonPath("$.questions").exists())
		.andExpect(jsonPath("$.openSessions").exists())
		.andExpect(jsonPath("$.closedSessions").exists())
		.andExpect(jsonPath("$.activeUsers").exists())
		.andExpect(jsonPath("$.interposedQuestions").exists());
	}

	@Test
	public void testShouldGetCacheControlHeaderForStatistics() throws Exception {
		mockMvc.perform(get("/statistics").accept(MediaType.APPLICATION_JSON))
		.andExpect(status().isOk())
		.andExpect(header().string("cache-control", "public, max-age=60"));
	}
}
