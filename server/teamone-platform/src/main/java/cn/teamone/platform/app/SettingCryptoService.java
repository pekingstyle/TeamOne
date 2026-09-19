package cn.teamone.platform.app;

import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 系统设置秘密值加解密服务（R-12/D6，全栈评审必改③）。
 *
 * <p><b>算法</b>：AES-256-GCM（12B 随机 IV/次，128bit 认证标签），输出 = IV || 密文+标签；
 * AAD = {@code group + ":" + key}——把密文绑死在它的坐标上，行间挪用/改组改名一律解密失败。</p>
 *
 * <p><b>密钥</b>：环境变量 {@code TEAMONE_CRYPTO_KEY}（Base64 解码后须恰 32 字节），
 * 不入库不进 git。<b>启动纪律</b>：未配置（或格式非法）仅 WARN，不炸启动——
 * 明文配置项照常可用；首次<b>写入秘密行</b>才 fail-fast，提示管理员配置密钥。
 * <b>解密纪律</b>：认证标签校验失败（密钥被更换/密文损坏）抛明确业务错误，不做静默兜底。</p>
 */
@Service
public class SettingCryptoService {

    private static final Logger log = LoggerFactory.getLogger(SettingCryptoService.class);

    /** 密钥环境变量名（仅作提示文案与文档引用，密钥本体只存在于环境） */
    public static final String KEY_ENV_NAME = "TEAMONE_CRYPTO_KEY";

    private static final String AES = "AES";
    private static final String GCM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 就绪密钥；null = 未配置/格式非法（明文项可用，秘密项读写 fail-fast） */
    private final SecretKey key;

    public SettingCryptoService(@Value("${teamone.crypto.key:}") String base64Key) {
        this.key = parseKey(base64Key);
    }

    /** 解析配置密钥：未配置/非法一律降级为「不可用」+ WARN（不炸启动，评审必改③的启动语义） */
    private static SecretKey parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("[setting-crypto] 环境变量 {} 未配置：秘密值暂不可读写（明文配置项不受影响），"
                    + "首次保存秘密项前请配置（Base64 编码的 32 字节密钥）", KEY_ENV_NAME);
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != KEY_BYTES) {
                log.warn("[setting-crypto] {} 长度非法（解码后 {} 字节，须 {} 字节）：秘密值暂不可读写",
                        KEY_ENV_NAME, raw.length, KEY_BYTES);
                return null;
            }
            return new SecretKeySpec(raw, AES);
        } catch (IllegalArgumentException e) {
            log.warn("[setting-crypto] {} 不是合法 Base64：秘密值暂不可读写", KEY_ENV_NAME);
            return null;
        }
    }

    /** 秘密值读写是否可用（密钥已配置且合法） */
    public boolean ready() {
        return key != null;
    }

    /**
     * 加密明文为 IV||密文（秘密行落库形）。密钥未就绪 → fail-fast（业务错误，提示配置环境变量）。
     *
     * @param plain 明文（调用方保证非空）
     * @param group 配置分组（AAD 坐标）
     * @param key   配置键名（AAD 坐标）
     */
    public byte[] encrypt(String plain, String group, String key) {
        requireReady();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(GCM);
            cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(group, key));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return out;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 加密侧异常（JCE 不可用等）属环境故障：不泄露明文，仅报「不可写」
            log.error("[setting-crypto] 加密失败（环境故障）: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SRV_5030, "秘密值加密失败，请稍后重试");
        }
    }

    /**
     * 解密库内密文（IV||密文）。认证标签校验失败（密钥被更换/密文被改动/坐标不符）
     * → 明确业务错误（评审必改③：错误密钥解密失败可识别），绝不静默返回空串。
     */
    public String decrypt(byte[] stored, String group, String key) {
        requireReady();
        if (stored == null || stored.length <= IV_BYTES) {
            throw decryptError();
        }
        try {
            Cipher cipher = Cipher.getInstance(GCM);
            cipher.init(Cipher.DECRYPT_MODE, this.key,
                    new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            cipher.updateAAD(aad(group, key));
            return new String(cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            // AEADBadTagException 等：密钥不匹配或密文损坏——统一收敛为可识别业务错误
            throw decryptError();
        }
    }

    private static BusinessException decryptError() {
        return new BusinessException(ErrorCode.PLT_4000,
                "秘密值解密失败：加密密钥不匹配或密文已损坏（" + KEY_ENV_NAME + " 是否已被更换？请重新配置该项）");
    }

    private void requireReady() {
        if (key == null) {
            throw new BusinessException(ErrorCode.PLT_4000,
                    "未配置环境变量 " + KEY_ENV_NAME + "（Base64 编码的 32 字节密钥），无法读写秘密值；"
                            + "请由管理员配置后重试");
        }
    }

    /** AAD = group + ":" + key：密文绑定坐标，防行间挪用（UTF-8 字节） */
    private static byte[] aad(String group, String key) {
        return (group + ":" + key).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 掩码投影（评审必改③：读取一律掩码）：保留前 4 位 + 固定 ****；
     * 空值/不足 4 位一律全掩码，避免短值泄露过多前缀。
     */
    public static String mask(String plain) {
        if (plain == null || plain.length() <= 4) {
            return "****";
        }
        return plain.substring(0, 4) + "****";
    }
}
