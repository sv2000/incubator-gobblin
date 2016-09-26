package gobblin.util;

import org.testng.Assert;
import org.testng.annotations.Test;


public class StringUtilsTest {

  @Test
  public void testHumanReadableToByteCount()
      throws Exception {
    Assert.assertEquals(StringUtils.humanReadableToByteCount("10"), 10L);
    Assert.assertEquals(StringUtils.humanReadableToByteCount("1k"), 1024L);
    Assert.assertEquals(StringUtils.humanReadableToByteCount("1m"), 1048576L);
    Assert.assertEquals(StringUtils.humanReadableToByteCount("1g"), 1073741824L);
    Assert.assertEquals(StringUtils.humanReadableToByteCount("1t"), 1099511627776L);

    Assert.assertEquals(StringUtils.humanReadableToByteCount("1K"), 1024L);
    Assert.assertEquals(StringUtils.humanReadableToByteCount("1kb"), 1024L);
    Assert.assertEquals(StringUtils.humanReadableToByteCount("1KB"), 1024L);

    Assert.assertEquals(StringUtils.humanReadableToByteCount("2k"), 2048L);
    Assert.assertEquals(StringUtils.humanReadableToByteCount("2.5k"), 2560L);
  }
}
