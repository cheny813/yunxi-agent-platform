package io.yunxi.platform.business.nutrition.provider;

import io.yunxi.platform.framework.spi.DomainContributorContractTest;

/**
 * SchoolMealDomainContributor 契约测试
 */
public class SchoolMealDomainContributorContractTest extends DomainContributorContractTest {

    private SchoolMealDomainContributor instance;

    @Override
    protected SchoolMealDomainContributor createInstance() {
        if (instance == null) {
            instance = new SchoolMealDomainContributor();
        }
        return instance;
    }
}
