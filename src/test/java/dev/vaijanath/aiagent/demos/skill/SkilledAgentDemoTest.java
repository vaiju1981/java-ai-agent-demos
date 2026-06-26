package dev.vaijanath.aiagent.demos.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vaijanath.aiagent.skill.KeywordSkillSelector;
import dev.vaijanath.aiagent.skill.Skill;
import dev.vaijanath.aiagent.skill.SkillRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkilledAgentDemoTest {

    private static List<String> selectedFor(String task) {
        SkillRegistry registry = SkilledAgentDemo.skills();
        return new KeywordSkillSelector().select(registry, task).stream().map(Skill::name).toList();
    }

    @Test
    void selectsTheMathSkillForAMathTask() {
        assertEquals(List.of("math-tutor"), selectedFor("What is 248 divided by 8? Teach me how."));
    }

    @Test
    void selectsTheTranslatorForATranslationTask() {
        assertEquals(List.of("french-translator"), selectedFor("How do you say hello in French?"));
    }
}
