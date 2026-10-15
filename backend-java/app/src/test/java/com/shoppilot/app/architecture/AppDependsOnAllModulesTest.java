package com.shoppilot.app.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则 2（POM 级占位断言）：app 必须【显式】声明对全部 10 个业务模块 + common 的依赖。
 *
 * <p>说明：ArchUnit 作用于字节码，无法对尚不存在的业务类断言「正向依赖」，
 * 故以 POM 声明作为该不变量的校验点（对应硬约束①：不靠传递依赖）。
 * 当 M2 业务类落地后，正向依赖可由 ArchUnit 的逐模块白名单规则覆盖。
 */
class AppDependsOnAllModulesTest {

    private static final Set<String> REQUIRED = Set.of(
            "shoppilot-common",
            "shoppilot-identity",
            "shoppilot-catalog",
            "shoppilot-importdata",
            "shoppilot-inventory",
            "shoppilot-sales",
            "shoppilot-analysis",
            "shoppilot-replenishment",
            "shoppilot-purchasing",
            "shoppilot-audit");

    @Test
    void appDeclaresAllBusinessModules() throws Exception {
        File pom = new File("pom.xml");
        assertTrue(pom.exists(), "app/pom.xml 应存在");

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(pom);
        doc.getDocumentElement().normalize();

        NodeList artifactIds = doc.getElementsByTagName("artifactId");
        Set<String> found = new HashSet<>();
        for (int i = 0; i < artifactIds.getLength(); i++) {
            found.add(artifactIds.item(i).getTextContent().trim());
        }

        for (String required : REQUIRED) {
            assertTrue(found.contains(required),
                    "app 的 <dependencies> 必须显式声明 " + required);
        }
    }
}
