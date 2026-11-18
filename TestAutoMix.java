import com.oneshot.surround.AutoMixSelector;
import java.util.*;
public class TestAutoMix {
  public static void main(String[] args) {
    AutoMixSelector selector = new AutoMixSelector();
    List<String> artists = Arrays.asList("A", "B", "C", "D");
    Map<Integer,AutoMixSelector.Features> features = new HashMap<>();
    features.put(0,new AutoMixSelector.Features(.15,120,0,false,.8));
    features.put(1,new AutoMixSelector.Features(.16,122,7,false,.8));
    features.put(2,new AutoMixSelector.Features(.5,170,1,true,.8));
    features.put(3,new AutoMixSelector.Features(.18,121,9,true,.8));
    selector.played(0);
    int next=selector.choose(0,artists,features);
    if(next!=1 && next!=3) throw new AssertionError("incompatible next: "+next);
    selector.played(next);
    int after=selector.choose(next,artists,features);
    if(after==0 || after==next) throw new AssertionError("recent repeat: "+after);
    if(selector.choose(0,Collections.singletonList("A"),features)!=-1) throw new AssertionError("single song");
    // Half/double-time BPM + outro->intro continuity check
    AutoMixSelector s2 = new AutoMixSelector();
    Map<Integer,AutoMixSelector.Features> f2 = new HashMap<>();
    f2.put(0, new AutoMixSelector.Features(.30, .20, .10, 140, 0, false, .8));
    f2.put(1, new AutoMixSelector.Features(.30, .11, .30, 70, 0, false, .8));
    f2.put(2, new AutoMixSelector.Features(.30, .80, .30, 95, 6, true, .8));
    int pick = s2.choose(0, Arrays.asList("X", "Y", "Z"), f2);
    if (pick != 1) throw new AssertionError("expected half-time + intro/outro match 1, got: " + pick);
    System.out.println("AutoMix selector passed: "+next+" then "+after);
  }
}
