package com.daaspro.apipub;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.daaspro.apipub.entity.ApiAppEntity;
import com.daaspro.apipub.entity.ApiGrantEntity;
import com.daaspro.apipub.mapper.ApiAppMapper;
import com.daaspro.apipub.mapper.ApiGrantMapper;
import com.daaspro.common.exception.BizException;
import com.daaspro.common.security.AesGcmCipher;
import com.daaspro.tenant.context.TenantContext;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

/**
 * API 应用（调用方凭证）。appKey/appSecret 生成后 secret AES-GCM 加密存储；
 * 明文仅在创建响应里返回一次，列表页脱敏展示。
 */
@Service
public class ApiAppService {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiAppMapper mapper;
    private final ApiGrantMapper grantMapper;
    private final AesGcmCipher cipher;

    public ApiAppService(ApiAppMapper mapper, ApiGrantMapper grantMapper, AesGcmCipher cipher) {
        this.mapper = mapper;
        this.grantMapper = grantMapper;
        this.cipher = cipher;
    }

    public record Created(Long id, String name, String appKey, String appSecret) {}

    public Created create(String name) {
        if (name == null || name.isBlank()) throw new BizException(400, "应用名称不能为空");
        Long dup = mapper.selectCount(new LambdaQueryWrapper<ApiAppEntity>()
                .eq(ApiAppEntity::getTenantId, TenantContext.requireTenantId())
                .eq(ApiAppEntity::getName, name.trim()));
        if (dup > 0) throw new BizException(400, "同名应用已存在");
        String appKey = random("ak_", 16);
        String secret = random("sk_", 32);
        ApiAppEntity app = new ApiAppEntity();
        app.setTenantId(TenantContext.requireTenantId());
        app.setName(name.trim());
        app.setAppKey(appKey);
        app.setAppSecretEnc(cipher.encrypt(secret));
        mapper.insert(app);
        return new Created(app.getId(), app.getName(), appKey, secret);
    }

    public List<ApiAppEntity> list() {
        return mapper.selectList(new LambdaQueryWrapper<ApiAppEntity>()
                .eq(ApiAppEntity::getTenantId, TenantContext.requireTenantId())
                .orderByDesc(ApiAppEntity::getId));
    }

    public String revealSecret(Long id) {
        return cipher.decrypt(getOwned(id).getAppSecretEnc());
    }

    public void rename(Long id, String name) {
        if (name == null || name.isBlank()) throw new BizException(400, "应用名称不能为空");
        ApiAppEntity app = getOwned(id);
        Long dup = mapper.selectCount(new LambdaQueryWrapper<ApiAppEntity>()
                .eq(ApiAppEntity::getTenantId, TenantContext.requireTenantId())
                .eq(ApiAppEntity::getName, name.trim())
                .ne(ApiAppEntity::getId, id));
        if (dup > 0) throw new BizException(400, "同名应用已存在");
        app.setName(name.trim());
        mapper.updateById(app);
    }

    public void delete(Long id) {
        ApiAppEntity app = getOwned(id);
        grantMapper.delete(new LambdaQueryWrapper<ApiGrantEntity>()
                .eq(ApiGrantEntity::getAppId, app.getId()));
        mapper.deleteById(id);
    }

    public ApiAppEntity getOwned(Long id) {
        ApiAppEntity app = mapper.selectById(id);
        if (app == null || !app.getTenantId().equals(TenantContext.requireTenantId())) {
            throw new BizException(404, "应用不存在");
        }
        return app;
    }

    /** 脱敏展示：sk_ab****yz（保留前后各 4 位） */
    public static String maskSecret(String plain) {
        if (plain == null || plain.length() <= 8) return "****";
        return plain.substring(0, 6) + "****" + plain.substring(plain.length() - 4);
    }

    private static String random(String prefix, int len) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
