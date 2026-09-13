import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class BcryptCheck {
  public static void main(String[] a){
    var e = new BCryptPasswordEncoder();
    String h="$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi";
    for(String p:new String[]{"admin123","admin","demo123","123456","demo123456"})
      System.out.println(p+" -> "+e.matches(p,h));
  }
}
