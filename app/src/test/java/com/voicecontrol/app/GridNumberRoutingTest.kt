package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridNumberRoutingTest {
    private val matcher = CommandMatcher.fromJson(
        """{"groups":[{"id":"commands","name":"命令","commands":[
          {"id":"volume_up","command":"增加音量","aliases":["声音大一点"],"action":"volume_up"},
          {"id":"volume_down","command":"降低音量","aliases":["声音小一点"],"action":"volume_down"},
          {"id":"cursor_left","command":"光标左移","aliases":[],"action":"text_cursor_left"},
          {"id":"nudge_left","command":"向左摇移","aliases":[],"action":"nudge_left"}
        ]}]}"""
    )

    @Test fun `网格宽松数字不抢正式命令里的数字同音字`() {
        assertNull(GridNumberRouting.extractLooseNumber("声音大一点", true, matcher))
        assertNull(GridNumberRouting.extractLooseNumber("声音小一点", true, matcher))
        assertNull(GridNumberRouting.extractLooseNumber("光标左移", true, matcher))
        assertNull(GridNumberRouting.extractLooseNumber("向左摇移", true, matcher))
    }

    @Test fun `网格纯数字仍可作为缩放意图`() {
        assertEquals(5, GridNumberRouting.extractLooseNumber("五", true, matcher))
        assertNull(GridNumberRouting.extractLooseNumber("五", false, matcher))
    }
}
