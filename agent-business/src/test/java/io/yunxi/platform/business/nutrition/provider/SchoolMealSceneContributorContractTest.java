package io.yunxi.platform.business.nutrition.provider;

import io.yunxi.platform.framework.spi.SceneContributorContractTest;

/**
 * SchoolMealSceneContributor 契约测试
 */
public class SchoolMealSceneContributorContractTest extends SceneContributorContractTest {

    private SchoolMealSceneContributor instance;

    @Override
    protected SchoolMealSceneContributor createInstance() {
        if (instance == null) {
            instance = new SchoolMealSceneContributor();
        }
        return instance;
    }
}
