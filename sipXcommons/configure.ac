AC_PREREQ(2.57)
AC_INIT(sipXcommons, 4.6.0, sipx-dev@list.sipfoundry.org)
AC_CONFIG_AUX_DIR(config)
m4_include([config/java2.m4])
m4_include([config/general.m4])
m4_include([config/sipXlib.m4])
AM_INIT_AUTOMAKE(foreign tar-ustar)
LT_INIT([disable-static])
AC_PROG_JAVA_CC([javac])
AC_PROG_JAVA([java])
CHECK_JDK
CHECK_JNI
SFAC_AUTOMAKE_VERSION([1.6])
SFAC_INIT_FLAGS
SFAC_SRCDIR_EXPAND
PROG_JAVA_DEP([${abs_srcdir}/bin])
AC_CONFIG_FILES([
	Makefile
	src/Makefile
	bin/Makefile
	lib/Makefile
	etc/Makefile
	src/main/Makefile
	src/main/java/Makefile
	src/main/native/Makefile
	src/test/Makefile
	src/test/java/Makefile
])
AC_OUTPUT
