package cn.teamone.platform.app;

import cn.teamone.shared.api.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 秘密值加解密服务单测（R-12/D6，评审必改③验收）：
 * 加解密往返 / 错误密钥解密失败可识别（auth tag）/ AAD 坐标绑定 / 掩码 / 密钥未配置 fail-fast。
 */
class SettingCryptoServiceTest {

    /** 测试密钥：32 字节确定性字节流（Base64） */
    private static final String KEY_A = Base64.getEncoder().encodeToString(bytes(0x11));
    private static final String KEY_B = Base64.getEncoder().encodeToString(bytes(0x22));

    private static byte[] bytes(int fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) fill);
        return raw;
    }

    private static SettingCryptoService ready() {
        return new SettingCryptoService(KEY_A);
    }

    @Test
    @DisplayName("加解密往返：密文非明文，解密还原原值")
    void roundtrip() {
        SettingCryptoService svc = ready();
        String plain = "ghp_abc123XYZ私有令牌内容-7890";
        byte[] stored = svc.encrypt(plain, "repo", "token");

        assertNotNull(stored);
        assertFalse(new String(stored, StandardCharsets.UTF_8).contains(plain), "密文不得包含明文");
        assertEquals(plain, svc.decrypt(stored, "repo", "token"), "解密须还原原值");
    }

    @Test
    @DisplayName("AAD 坐标绑定：换坐标解密必须失败（防密文行间挪用）")
    void aadBinding() {
        SettingCryptoService svc = ready();
        byte[] stored = svc.encrypt("my-secret", "repo", "token");

        assertThrows(BusinessException.class,
                () -> svc.decrypt(stored, "credential", "token"), "换组解密应失败");
        assertThrows(BusinessException.class,
                () -> svc.decrypt(stored, "repo", "token2"), "换键解密应失败");
    }

    @Test
    @DisplayName("错误密钥解密失败可识别：auth tag 校验不过 → 明确业务错误（非静默/非 5xx）")
    void wrongKeyFailsRecognizably() {
        SettingCryptoService svcA = ready();
        byte[] stored = svcA.encrypt("my-secret", "repo", "token");

        // 同一坐标、不同密钥：模拟 TEAMONE_CRYPTO_KEY 被更换后的解密
        SettingCryptoService svcB = new SettingCryptoService(KEY_B);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svcB.decrypt(stored, "repo", "token"));
        assertTrue(ex.getMessage().contains("解密失败"), "错误消息须可识别（解密失败）");
        assertTrue(ex.getMessage().contains("TEAMONE_CRYPTO_KEY"), "错误消息须指向密钥配置");

        // 密文被篡改同样可识别
        stored[stored.length - 1] ^= 0x01;
        assertThrows(BusinessException.class, () -> svcA.decrypt(stored, "repo", "token"));
    }

    @Test
    @DisplayName("密钥未配置：启动不炸（ready=false），首次读写秘密值 fail-fast 且提示配置")
    void missingKeyFailsFastOnUse() {
        SettingCryptoService svc = new SettingCryptoService("");
        assertFalse(svc.ready(), "未配置时 ready 须为 false");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.encrypt("x", "repo", "token"));
        assertTrue(ex.getMessage().contains("TEAMONE_CRYPTO_KEY"), "fail-fast 消息须指向环境变量");

        assertThrows(BusinessException.class, () -> svc.decrypt(new byte[20], "repo", "token"));
    }

    @Test
    @DisplayName("密钥格式非法（非 Base64 / 非 32 字节）：按未配置处理，启动不炸")
    void malformedKeyTreatedAsMissing() {
        assertFalse(new SettingCryptoService("not-base64!!").ready());
        assertFalse(new SettingCryptoService(Base64.getEncoder().encodeToString(new byte[16])).ready());
    }

    @Test
    @DisplayName("掩码：前 4 位 + ****；空值/短值全掩码")
    void mask() {
        assertEquals("ghp_****", SettingCryptoService.mask("ghp_abc123"));
        assertEquals("****", SettingCryptoService.mask("abc"));
        assertEquals("****", SettingCryptoService.mask(""));
        assertEquals("****", SettingCryptoService.mask(null));
        assertEquals("abcd****", SettingCryptoService.mask("abcdef"));
    }

    @Test
    @DisplayName("每次加密 IV 随机：同一明文两次加密产出不同密文")
    void randomIv() {
        SettingCryptoService svc = ready();
        byte[] c1 = svc.encrypt("same-plain", "repo", "token");
        byte[] c2 = svc.encrypt("same-plain", "repo", "token");
        assertFalse(java.util.Arrays.equals(c1, c2), "随机 IV 下密文不得相同");
        assertEquals("same-plain", svc.decrypt(c1, "repo", "token"));
        assertEquals("same-plain", svc.decrypt(c2, "repo", "token"));
    }
}
