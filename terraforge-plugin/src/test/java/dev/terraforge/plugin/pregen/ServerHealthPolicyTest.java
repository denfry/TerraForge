package dev.terraforge.plugin.pregen;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class ServerHealthPolicyTest {
 @Test void requiresAnUninterruptedStableWindow() { var p=new ServerHealthPolicy(true,18,40,10,15); var ok=new ServerHealthSnapshot(0,20,20,20,true,false,false); var t=Instant.EPOCH; assertThat(p.evaluate(ok,t).reason()).isEqualTo("stabilizing"); assertThat(p.evaluate(ok,t.plusSeconds(15)).mayDispatch()).isTrue(); assertThat(p.evaluate(new ServerHealthSnapshot(1,20,20,20,true,false,false),t.plusSeconds(16)).reason()).isEqualTo("players-online"); assertThat(p.evaluate(ok,t.plusSeconds(17)).reason()).isEqualTo("stabilizing"); }
}
