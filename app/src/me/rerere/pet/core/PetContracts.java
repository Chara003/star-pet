package me.rerere.pet.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** 核心契约：动作语义、身体区域、精灵图版本。与 Kotlin 参考实现保持一致的协议。 */
public final class PetContracts {
    private PetContracts() {}

    /** 语义动作。renderer 只播放已通过 Resolver 校验的动作。 */
    public enum PetAction {
        IDLE, MOVE_RIGHT, MOVE_LEFT, WAVE, JUMP, FAILURE, WAIT, WORK, REVIEW;
    }

    /** 触摸命中区域。 */
    public enum PetBodyRegion { HEAD, BODY, FEET, UNKNOWN }

    /** 精灵图版本决定行数。 */
    public enum CodexPetVersion {
        V1(9), V2(11);
        private final int rows;
        CodexPetVersion(int rows) { this.rows = rows; }
        public int rows() { return rows; }
        public static CodexPetVersion of(int v) {
            if (v == 1) return V1;
            if (v == 2) return V2;
            throw new IllegalArgumentException("pet_manifest_version_unsupported");
        }
    }

    /** 图集固定规格：单帧 192x208，8 列。 */
    public static final int CODEX_FRAME_WIDTH = 192;
    public static final int CODEX_FRAME_HEIGHT = 208;
    public static final int CODEX_ATLAS_COLUMNS = 8;

    public static final int MAX_PET_INPUT_CODE_POINTS = 500;
    public static final int MAX_PET_RESPONSE_CODE_POINTS = 96;
    public static final int MAX_PET_DIALOGUE_ROUNDS = 20;

    public static final float MIN_SCALE = 0.05f;
    public static final float MAX_SCALE = 3.0f;
    public static final int MIN_FPS = 4;
    public static final int MAX_FPS = 12;

    /** 头/身 双边界（百分比 0..1）。 */
    public static final float MIN_HEAD_BOUNDARY = 0.05f;
    public static final float MAX_HEAD_BOUNDARY = 0.90f;
    public static final float MIN_BODY_BOUNDARY = 0.10f;
    public static final float MAX_BODY_BOUNDARY = 0.99f;

    private static final Pattern SAFE_PACKAGE_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
    private static final Pattern SAFE_PROFILE_ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    public static boolean isValidPackageId(String v) { return v != null && SAFE_PACKAGE_ID.matcher(v).matches(); }
    public static boolean isValidProfileId(String v) { return v != null && SAFE_PROFILE_ID.matcher(v).matches(); }

    /** 把手势名映射为交互意图。 */
    public enum GestureAction { LOCAL_FEEDBACK, MODEL_RESPONSE, QUICK_MENU, DIALOGUE }

    public static GestureAction gestureAction(String gesture) {
        if (gesture == null) return GestureAction.LOCAL_FEEDBACK;
        switch (gesture) {
            case "tap": case "pat": return GestureAction.MODEL_RESPONSE;
            case "long_press": return GestureAction.DIALOGUE;
            case "double_tap": return GestureAction.QUICK_MENU;
            default: return GestureAction.LOCAL_FEEDBACK;
        }
    }

    /** 视觉情绪提示（模型只输出语义，不允许指定动作名或资源）。 */
    public enum VisualHint { NEUTRAL, GREETING, PLAYFUL, CURIOUS, COMFORTING;
        public static VisualHint parse(String s) {
            if (s == null) return NEUTRAL;
            try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); }
            catch (Exception e) { return NEUTRAL; }
        }
    }

    public static PetAction visualHintToAction(VisualHint hint) {
        switch (hint) {
            case NEUTRAL: return PetAction.REVIEW;
            case GREETING: return PetAction.WAVE;
            case PLAYFUL: return PetAction.JUMP;
            case CURIOUS: return PetAction.REVIEW;
            case COMFORTING: return PetAction.WAVE;
            default: return PetAction.IDLE;
        }
    }

    /** 会话归档策略。 */
    public enum SessionRollAction { NONE, ROLL_EMPTY_DATE, ARCHIVE_DAILY, ARCHIVE_CAPACITY }

    public static SessionRollAction beforeAppend(String currentDate, String targetDate, int turnCount) {
        boolean rollover = currentDate != null && targetDate != null && !currentDate.equals(targetDate);
        if (rollover && turnCount == 0) return SessionRollAction.ROLL_EMPTY_DATE;
        if (rollover) return SessionRollAction.ARCHIVE_DAILY;
        if (turnCount >= MAX_PET_DIALOGUE_ROUNDS) return SessionRollAction.ARCHIVE_CAPACITY;
        return SessionRollAction.NONE;
    }
}