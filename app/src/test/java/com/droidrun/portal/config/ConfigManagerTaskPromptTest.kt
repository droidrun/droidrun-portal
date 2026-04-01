package com.droidrun.portal.config

import android.content.Context
import android.content.SharedPreferences
import com.droidrun.portal.taskprompt.PortalActiveTaskRecord
import com.droidrun.portal.taskprompt.PortalCloudClient
import com.droidrun.portal.taskprompt.PortalTaskSettings
import com.droidrun.portal.taskprompt.PortalTaskTracking
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

class ConfigManagerTaskPromptTest {

    private lateinit var context: Context
    private lateinit var sharedStore: MutableMap<String, Any?>
    private lateinit var deviceStore: MutableMap<String, Any?>

    @Before
    fun setUp() {
        clearSingleton()
        sharedStore = mutableMapOf()
        deviceStore = mutableMapOf()
        context = mockContext(sharedStore, deviceStore)
    }

    @After
    fun tearDown() {
        clearSingleton()
    }

    @Test
    fun taskPromptSettings_readsDefaults() {
        val configManager = ConfigManager.getInstance(context)

        val settings = configManager.taskPromptSettings

        assertEquals(PortalCloudClient.DEFAULT_MODEL_ID, settings.llmModel)
        assertEquals(PortalCloudClient.DEFAULT_REASONING, settings.reasoning)
        assertEquals(PortalCloudClient.DEFAULT_VISION, settings.vision)
        assertEquals(PortalCloudClient.DEFAULT_MAX_STEPS, settings.maxSteps)
        assertEquals(PortalCloudClient.DEFAULT_TEMPERATURE, settings.temperature, 0.0)
        assertEquals(PortalCloudClient.DEFAULT_EXECUTION_TIMEOUT, settings.executionTimeout)
    }

    @Test
    fun saveTaskPromptSettings_persistsAllFields() {
        val initial = ConfigManager.getInstance(context)
        initial.saveTaskPromptSettings(
            PortalTaskSettings(
                llmModel = "openai/gpt-5.2",
                reasoning = true,
                vision = true,
                maxSteps = 444,
                temperature = 1.25,
                executionTimeout = 777,
            ),
        )

        clearSingleton()

        val restored = ConfigManager.getInstance(context).taskPromptSettings

        assertEquals("openai/gpt-5.2", restored.llmModel)
        assertEquals(true, restored.reasoning)
        assertEquals(true, restored.vision)
        assertEquals(444, restored.maxSteps)
        assertEquals(1.25, restored.temperature, 0.0)
        assertEquals(777, restored.executionTimeout)
        assertFalse(sharedStore.isEmpty())
    }

    @Test
    fun taskPromptSettings_prefers_saved_default_model_when_no_explicit_model_exists() {
        val configManager = ConfigManager.getInstance(context)

        configManager.updateTaskPromptDefaultModel("google/gemini-3-flash")
        configManager.taskPromptModel = ""

        val settings = configManager.taskPromptSettings

        assertEquals("google/gemini-3-flash", settings.llmModel)
    }

    @Test
    fun taskPromptSettings_keeps_explicit_model_over_loaded_default() {
        val configManager = ConfigManager.getInstance(context)

        configManager.updateTaskPromptDefaultModel("google/gemini-3-flash")
        configManager.taskPromptModel = "openai/gpt-5.1"

        val settings = configManager.taskPromptSettings

        assertEquals("openai/gpt-5.1", settings.llmModel)
    }

    @Test
    fun taskPromptReturnToPortal_persistsSelection() {
        val initial = ConfigManager.getInstance(context)
        initial.taskPromptReturnToPortal = true

        clearSingleton()

        assertEquals(true, ConfigManager.getInstance(context).taskPromptReturnToPortal)
    }

    @Test
    fun activePortalTask_persistsAndRestoresRecord() {
        val initial = ConfigManager.getInstance(context)
        initial.saveActivePortalTask(
            PortalActiveTaskRecord(
                taskId = "task-123",
                promptPreview = "Open the camera app",
                startedAtMs = 123456789L,
                executionTimeoutSec = 900,
                pollDeadlineMs = 123457689L,
                lastStatus = PortalTaskTracking.STATUS_RUNNING,
                startedToastShown = true,
                terminalToastShown = true,
                triggerRuleId = "rule-42",
                returnToPortalOnTerminal = true,
                terminalReturnHandled = true,
                terminalTransitionHandled = true,
            ),
        )

        clearSingleton()

        val restored = ConfigManager.getInstance(context).activePortalTask

        requireNotNull(restored)
        assertEquals("task-123", restored.taskId)
        assertEquals("Open the camera app", restored.promptPreview)
        assertEquals(123456789L, restored.startedAtMs)
        assertEquals(900, restored.executionTimeoutSec)
        assertEquals(123457689L, restored.pollDeadlineMs)
        assertEquals(PortalTaskTracking.STATUS_RUNNING, restored.lastStatus)
        assertEquals(true, restored.startedToastShown)
        assertEquals(true, restored.terminalToastShown)
        assertEquals("rule-42", restored.triggerRuleId)
        assertEquals(true, restored.returnToPortalOnTerminal)
        assertEquals(true, restored.terminalReturnHandled)
        assertEquals(true, restored.terminalTransitionHandled)
    }

    private fun mockContext(
        sharedPrefsStore: MutableMap<String, Any?>,
        devicePrefsStore: MutableMap<String, Any?>,
    ): Context {
        val context = mockk<Context>(relaxed = true)
        val sharedPrefs = mockPreferences(sharedPrefsStore)
        val devicePrefs = mockPreferences(devicePrefsStore)

        every { context.applicationContext } returns context
        every {
            context.getSharedPreferences("droidrun_config", Context.MODE_PRIVATE)
        } returns sharedPrefs
        every {
            context.getSharedPreferences("droidrun_device", Context.MODE_PRIVATE)
        } returns devicePrefs

        return context
    }

    private fun mockPreferences(store: MutableMap<String, Any?>): SharedPreferences {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()

        every { prefs.contains(any()) } answers { store.containsKey(firstArg()) }
        every { prefs.getString(any(), any()) } answers {
            store[firstArg<String>()] as? String ?: secondArg<String?>()
        }
        every { prefs.getBoolean(any(), any()) } answers {
            store[firstArg<String>()] as? Boolean ?: secondArg<Boolean>()
        }
        every { prefs.getInt(any(), any()) } answers {
            store[firstArg<String>()] as? Int ?: secondArg<Int>()
        }
        every { prefs.getLong(any(), any()) } answers {
            store[firstArg<String>()] as? Long ?: secondArg<Long>()
        }
        every { prefs.getFloat(any(), any()) } answers {
            store[firstArg<String>()] as? Float ?: secondArg<Float>()
        }
        every { prefs.edit() } returns editor

        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            store[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            store[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            store[firstArg()] = secondArg<Long>()
            editor
        }
        every { editor.putFloat(any(), any()) } answers {
            store[firstArg()] = secondArg<Float>()
            editor
        }
        every { editor.remove(any()) } answers {
            store.remove(firstArg<String>())
            editor
        }
        every { editor.clear() } answers {
            store.clear()
            editor
        }
        every { editor.apply() } just Runs
        every { editor.commit() } returns true

        return prefs
    }

    private fun clearSingleton() {
        val owners = listOf(ConfigManager::class.java, ConfigManager.Companion::class.java)
        for (owner in owners) {
            val field = owner.declaredFields.firstOrNull { it.name == "INSTANCE" } ?: continue
            field.isAccessible = true
            val receiver = if (Modifier.isStatic(field.modifiers)) null else ConfigManager.Companion
            field.set(receiver, null)
            return
        }
        error("ConfigManager INSTANCE field not found")
    }
}
