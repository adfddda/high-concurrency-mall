package com.lxt.mall.boot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.User;
import com.lxt.mall.domain.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 演示账号初始化。
 *
 * <p>存在的理由：SQL 脚本里硬编码 BCrypt 密文是个坑——BCrypt 每次加密都带随机盐，
 * 不同 cost 参数/不同实现写出来的密文不通用，一旦对不上就是"密码明明是 123456
 * 却登录失败"这种极难排查的问题。改成启动时用应用自己的 PasswordEncoder
 * 现场生成，就永远不会不一致。
 *
 * <p>同时它也负责批量造用户，供压测脚本使用：一人一单的秒杀场景
 * 必须有足够多的不同用户，否则压测结果全被"重复购买"短路，
 * 测出来的根本不是扣减链路的真实能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.demo.seed-enabled", havingValue = "true")
public class DataSeeder implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final MallProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        MallProperties.Demo demo = properties.getDemo();

        // 一次性把已有用户名捞出来在内存里比对，而不是每个用户一次 selectCount：
        // 200 个账号就是 200 次往返，启动阶段白白多花几百毫秒。
        Set<String> existing = userMapper.selectList(
                        Wrappers.<User>lambdaQuery().select(User::getUsername))
                .stream().map(User::getUsername).collect(Collectors.toSet());

        List<String> wanted = new ArrayList<>();
        wanted.add("lxt");
        for (int i = 1; i <= demo.getUserCount(); i++) {
            wanted.add(demo.getUsernamePrefix() + i);
        }
        List<String> missing = wanted.stream().filter(u -> !existing.contains(u)).toList();
        if (missing.isEmpty()) {
            log.info("演示账号已就绪（库中已有 {} 个用户），跳过初始化", existing.size());
            return;
        }

        // BCrypt 只算一次：同一个明文密码复用同一个密文即可。
        // 逐个加密 200 次会让启动多花十几秒，而且没有任何安全收益。
        String encoded = passwordEncoder.encode(demo.getPassword());
        for (String username : missing) {
            userMapper.insert(buildUser(username, demo.getNicknamePrefix() + "-" + username, encoded));
        }
        log.info("已初始化 {} 个演示账号，统一密码={}，示例账号: lxt / {}",
                missing.size(), demo.getPassword(), demo.getPassword());
    }

    private User buildUser(String username, String nickname, String encodedPassword) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodedPassword);
        user.setNickname(nickname);
        user.setStatus(1);
        return user;
    }
}
