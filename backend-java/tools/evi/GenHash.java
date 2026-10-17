import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class GenHash {
  public static void main(String[] a){
    System.out.println(new BCryptPasswordEncoder().encode("S3cret!Passw0rd"));
  }
}
