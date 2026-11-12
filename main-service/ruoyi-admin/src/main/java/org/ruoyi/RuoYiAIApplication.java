package org.ruoyi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;


/**
 * 启动程序
 *
 * @author xiaoqh
 */
@Slf4j
@SpringBootApplication
public class RuoYiAIApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RuoYiAIApplication.class);
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
        log.info("(♥◠‿◠)ﾉﾞ   MJN-AI启动成功   ლ(´ڡ`ლ)");
    }

}

