package sn.samapiece.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvenementAuditRepositoryContractTest {

    @Test
    void repository_neDoitExposerAucuneMethodeDeleteOuUpdate() {
        List<String> methodesInterdites = Arrays.stream(EvenementAuditRepository.class.getMethods())
                .map(Method::getName)
                .filter(nom -> nom.startsWith("delete") || nom.startsWith("update"))
                .toList();

        assertThat(methodesInterdites).isEmpty();
    }
}
