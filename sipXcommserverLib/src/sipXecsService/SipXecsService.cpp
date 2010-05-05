//
// Copyright (C) 2007 Pingtel Corp., certain elements licensed under a Contributor Agreement.
// Contributors retain copyright to elements licensed under a Contributor Agreement.
// Licensed to the User under the LGPL license.
//
//////////////////////////////////////////////////////////////////////////////

// SYSTEM INCLUDES

// APPLICATION INCLUDES
#include "os/OsSysLog.h"
#include "os/OsConfigDb.h"
#include "sipXecsService/SipXecsService.h"

// DEFINES
// CONSTANTS
const char* DomainConfigurationName = "domain-config";
const char* CONFIG_SETTINGS_FILE_SUFFIX = "-config";
const char* CONFIG_ETC_DIR          = SIPX_CONFDIR;
const char* CONFIG_LOG_DIR          = SIPX_LOGDIR;

const char* LogLevelSuffix = "_LOG_LEVEL";

const char* SipXecsService::DefaultConfigurationDir = SIPX_CONFDIR;
const char* SipXecsService::DefaultLocalStateDir    = SIPX_VARDIR;
const char* SipXecsService::DefaultLogDir           = SIPX_LOGDIR;
const char* SipXecsService::DefaultRunDir           = SIPX_RUNDIR;
const char* SipXecsService::DefaultTmpDir           = SIPX_TMPDIR;
const char* SipXecsService::DefaultDatabaseDir      = SIPX_DBDIR;
const char* SipXecsService::DefaultVarDir           = SIPX_VARDIR;
const char* SipXecsService::DefaultDataDir          = SIPX_DATADIR;
const char* SipXecsService::DefaultBinDir           = SIPX_BINDIR;
const char* SipXecsService::DefaultLibExecDir       = SIPX_LIBEXECDIR;
const char* SipXecsService::DefaultUser             = SIPXPBXUSER;
const char* SipXecsService::DefaultGroup            = SIPXPBXGROUP;
const char* SipXecsService::DefaultName             = SIPXECS_NAME;

DirectoryType SipXecsService::ConfigurationDirType = "SIPX_CONFDIR";
DirectoryType SipXecsService::LocalStateDirType    = "SIPX_VARDIR";
DirectoryType SipXecsService::LogDirType           = "SIPX_LOGDIR";
DirectoryType SipXecsService::RunDirType           = "SIPX_RUNDIR";
DirectoryType SipXecsService::TmpDirType           = "SIPX_TMPDIR";
DirectoryType SipXecsService::DatabaseDirType      = "SIPX_DBDIR";
DirectoryType SipXecsService::VarDirType           = "SIPX_VARDIR";
DirectoryType SipXecsService::DataDirType          = "SIPX_DATADIR";
DirectoryType SipXecsService::BinDirType           = "SIPX_BINDIR";
DirectoryType SipXecsService::LibExecDirType       = "SIPX_LIBEXECDIR";
DirectoryType SipXecsService::NameType             = "SIPXECS_NAME";

// lookup keys for the domain configuration
const char* SipXecsService::DomainDbKey::SIP_DOMAIN_NAME = "SIP_DOMAIN_NAME";
const char* SipXecsService::DomainDbKey::SIP_DOMAIN_ALIASES = "SIP_DOMAIN_ALIASES";
const char* SipXecsService::DomainDbKey::SIP_REALM       = "SIP_REALM";
const char* SipXecsService::DomainDbKey::SHARED_SECRET   = "SHARED_SECRET";
const char* SipXecsService::DomainDbKey::SUPERVISOR_PORT = "SUPERVISOR_PORT";
const char* SipXecsService::DomainDbKey::CONFIG_HOSTS    = "CONFIG_HOSTS";

// commands received over stdin pipe
const char* CONFIG_CHANGED_MSG = "CONFIG_CHANGED";

// TYPEDEFS
// FORWARD DECLARATIONS

/// constructor
SipXecsService::SipXecsService(const char* serviceName, const char* serviceConfigPrefix, const char* version)
   : mServiceName(serviceName),
     mServiceConfigPrefix(serviceConfigPrefix),
     mConfigDb(NULL)
{
   // Block all signals in this the main thread.
   // Any threads created from now on will have all signals masked.
   OsTask::blockSignals();

   OsSysLog::initialize(0, mServiceName.data());

   // Set up logfile initially with NOTICE level.
   UtlString logFileName;
   logFileName.append(mServiceName);
   logFileName.append(".log");

   OsPath logFilePath = Path(LogDirType, logFileName);
   OsSysLog::setOutputFile(0, logFilePath.data());
   OsSysLog::setLoggingPriority(PRI_NOTICE);

   OsSysLog::enableConsoleOutput(false);
   OsSysLog::add(FAC_KERNEL, PRI_NOTICE, "%s %s >>>>>>>>>>>>>>>> STARTED",
                 mServiceName.data(), version
                 );

   // Create a new task to wait for signals.  Only that task
   // will ever see a signal from the outside.
   mSignalTask = new ChildSignalTask();
   mSignalTask->start();

   // Create the stdin listener
   mStdinListenerTask = new StdinListenerTask(this);
   mStdinListenerTask->start();

   // load the main config file in order for the log level to be set properly.
   loadConfig();
};


// Given a path type, return the default directory for files of that type.
const char* SipXecsService::defaultDir(DirectoryType pathType)
{
   const char* returnDir = NULL;

   if (ConfigurationDirType == pathType)
   {
      returnDir = DefaultConfigurationDir;
   }
   else if (LocalStateDirType == pathType)
   {
      returnDir = DefaultLocalStateDir;
   }
   else if (LogDirType == pathType)
   {
      returnDir = DefaultLogDir;
   }
   else if (RunDirType == pathType)
   {
      returnDir = DefaultRunDir;
   }
   else if (TmpDirType == pathType)
   {
      returnDir = DefaultTmpDir;
   }
   else if (DatabaseDirType == pathType)
   {
      returnDir = DefaultDatabaseDir;
   }
   else if (VarDirType == pathType)
   {
      returnDir = DefaultVarDir;
   }
   else if (DataDirType == pathType)
   {
      returnDir = DefaultDataDir;
   }
   else if (BinDirType == pathType)
   {
      returnDir = DefaultBinDir;
   }
   else if (LibExecDirType == pathType)
   {
      returnDir = DefaultLibExecDir;
   }
   else
   {
      // invalid directory type
      OsSysLog::add(FAC_KERNEL, PRI_CRIT, "SipXecsService::defaultDir Invalid DirectoryType '%s'",
                    pathType);
      assert(false);
   }

   return returnDir;
}

OsPath SipXecsService::Path(DirectoryType pathType, const char* fileName)
{
   OsPath path;

   const char* dirPath;
   if ( (dirPath = getenv(pathType)) )
   {
      OsSysLog::add(FAC_KERNEL, PRI_NOTICE,
                    "SipXecsService::Path type '%s' overridden by environment to '%s'",
                    pathType, dirPath);
   }
   else
   {
      dirPath = defaultDir(pathType);
   }
   path.append(dirPath);

   const char slash = OsPath::separator(0);
   const char lastPathChar = path(path.length()-1);
   if (fileName && *fileName != '\000')
   {
      // Add the file name
      //   make sure there is exactly one separator between the directory and the file
      if (   slash != lastPathChar
          && slash != fileName[0]
          )
      {
         // neither has separator - add one
         path.append(OsPath::separator);
      }
      else if (   slash == lastPathChar
               && slash == fileName[0]
               )
      {
         // both have the separator - take one off so there's only one
         path.remove(path.length()-1);
      }

      path.append(fileName);
   }
   // There is no file name, so make sure the returned directory name does not
   // end in a separator
   else if ( slash == lastPathChar )
   {
      path.remove(path.length()-1);
   }

   OsSysLog::add(FAC_KERNEL, PRI_DEBUG,
                 "SipXecsService::Path('%s', '%s') returning '%s'",
                 pathType, fileName ? fileName : "", path.data() );
   return path;
}

/// Open the configuration common to all services in the domain.
OsPath SipXecsService::domainConfigPath()
{
   return Path(ConfigurationDirType, DomainConfigurationName);
}

/// Default user name for a sipXecs service
const char* SipXecsService::User()
{
   return DefaultUser;
}

/// Default process group name for a sipXecs service
const char* SipXecsService::Group()
{
   return DefaultGroup;
}

/// Name for the sipXecs system (can be overridden by environment or configure)
const char* SipXecsService::Name()
{
   const char* name;
   if ( (name = getenv(NameType)) )
   {
      OsSysLog::add(FAC_KERNEL, PRI_NOTICE,
                    "SipXecsService::Name overridden by environment to '%s'",
                    name);
   }
   else
   {
      name = DefaultName;
   }
   return name;
}

OsConfigDb* SipXecsService::loadConfig()
{
   // Configuration Database (used for OsSysLog)
   mConfigDb = new OsConfigDb();

   // Load configuration file.
   OsPath workingDirectory;
   if (OsFileSystem::exists(CONFIG_ETC_DIR))
   {
      workingDirectory = CONFIG_ETC_DIR;
      OsPath path(workingDirectory);
      path.getNativePath(workingDirectory);
   }
   else
   {
      OsPath path;
      OsFileSystem::getWorkingDirectory(path);
      path.getNativePath(workingDirectory);
   }

   mWorkingDirectory = workingDirectory;
   UtlString fileName =  workingDirectory +
      OsPathBase::separator + mServiceName +
      CONFIG_SETTINGS_FILE_SUFFIX;

   if (mConfigDb->loadFromFile(fileName) != OS_SUCCESS)
   {
      fprintf(stderr, "Failed to load %s", fileName.data());
      exit(1);
   }

   // Set log priority from config file
   SipXecsService::setLogPriority(*mConfigDb, mServiceConfigPrefix);

   return mConfigDb;
}

OsConfigDb* SipXecsService::reloadConfig()
{
   // Configuration Database (used for OsSysLog)
   OsConfigDb* configDb = new OsConfigDb();

   UtlString fileName =  mWorkingDirectory +
      OsPathBase::separator + mServiceName +
      CONFIG_SETTINGS_FILE_SUFFIX;
   if (configDb && configDb->loadFromFile(fileName) != OS_SUCCESS)
   {
      OsSysLog::add(FAC_KERNEL, PRI_CRIT, "Could not reload configuration file %s",
                    fileName.data());
      configDb = NULL;
   }

   return configDb;
}

// Initialize the OsSysLog
void SipXecsService::initSysLog(OsConfigDb* pConfig)
{
   //
   // Get/Apply Log Level
   //
   SipXecsService::setLogPriority(*pConfig, mServiceConfigPrefix);
}

OsSysLogPriority SipXecsService::setLogPriority(const char* configSettingsFile, // path to configuration file
                                    const char* servicePrefix, /* the string "_LOG_LEVEL" is
                                                                * appended to this prefix to find
                                                                * the config directive that sets
                                                                * the level */
                                    OsSysLogPriority defaultLevel /* used if no directive
                                                                   * is found, or the value
                                                                   * found is not a valid
                                                                   * level name */
                                    )
{
   OsConfigDb configuration;

   OsPath configPath = SipXecsService::Path(SipXecsService::ConfigurationDirType,
                                            configSettingsFile);

   if (OS_SUCCESS == configuration.loadFromFile(configPath.data()))
   {
      return setLogPriority(configuration, servicePrefix, defaultLevel);
   }
   else
   {
      OsSysLog::add(FAC_KERNEL, PRI_WARNING,
                    "SipXecsService::setLogPriority: Failed to open config file at '%s'\n"
                    "  setting %s%s to %s",
                    configPath.data(),
                    servicePrefix, LogLevelSuffix, OsSysLog::priorityName(defaultLevel)
                    );

      OsSysLog::setLoggingPriority(defaultLevel);
      return defaultLevel;
   }
}

OsSysLogPriority SipXecsService::setLogPriority(const OsConfigDb& configSettings, // configuration data
                                    const char* servicePrefix, /* the string "_LOG_LEVEL" is
                                                                * appended to this prefix to
                                                                * find the config directive that
                                                                * sets the level */
                                    OsSysLogPriority defaultLevel // default default is "NOTICE"
                                    )
{
   UtlString logLevel;
   UtlString logLevelTag(servicePrefix);
   logLevelTag.append(LogLevelSuffix);

   configSettings.get(logLevelTag, logLevel);

   OsSysLogPriority priority;
   if ( logLevel.isNull() )
   {
      OsSysLog::add(FAC_KERNEL,PRI_WARNING,
                    "SipXecsService::setLogPriority: %s not found, using '%s'",
                    logLevelTag.data(), OsSysLog::priorityName(defaultLevel)
                    );

      priority = defaultLevel;
   }
   else if ( ! OsSysLog::priority(logLevel.data(), priority))
   {
      OsSysLog::add(FAC_KERNEL,PRI_ERR,
                    "SipXecsService::setLogPriority: %s value '%s' is invalid, using '%s'",
                    logLevelTag.data(), logLevel.data(), OsSysLog::priorityName(defaultLevel)
                    );

      priority = defaultLevel;

   }

   OsSysLog::setLoggingPriority(priority);
   OsSysLog::add(FAC_KERNEL, priority, "Log level set to %s", OsSysLog::priorityName(priority));
   return priority;
}

/// Handle changed config file.  Services can override this function if desired.
/// The default is to adjust the log level.
void SipXecsService::configDbChanged(UtlString& configFile)
{
   OsSysLog::add(FAC_KERNEL, PRI_INFO,
                 "SipXecsService::configDbChanged: %s",
                 configFile.data() );
   // Suck in the new log level
   OsConfigDb* newConfigDb = reloadConfig();
   if (newConfigDb != NULL)
   {
      // Change the log level
      UtlString oldLogLevel;
      UtlString newLogLevel;
      UtlString logLevelTag(mServiceConfigPrefix);
      logLevelTag.append(LogLevelSuffix);

      mConfigDb->get(logLevelTag, oldLogLevel);
      newConfigDb->get(logLevelTag, newLogLevel);
      if (oldLogLevel != newLogLevel)
      {
         SipXecsService::setLogPriority(*newConfigDb, mServiceConfigPrefix);
      }

      delete mConfigDb;
      mConfigDb = newConfigDb;
   }
}

/// Handle changed configuration file.  Services can override this function if desired.
/// The default is to ignore the change.
// Note that this method is called in the StdinListener thread, so the implementation must be threadsafe.
void SipXecsService::resourceChanged(UtlString& fileType, UtlString& configFile)
{
   OsSysLog::add(FAC_KERNEL, PRI_INFO,
                 "SipXecsService::resourceChanged: %s (type %s), ignored",
                 configFile.data(), fileType.data() );
}

/// Process input that has been received
void SipXecsService::gotInput(UtlString& stdinMsg)
{
   ssize_t msgPos = stdinMsg.index(CONFIG_CHANGED_MSG);
   if (msgPos != UtlString::UTLSTRING_NOT_FOUND)
   {
      UtlString file = stdinMsg.replace(msgPos, strlen(CONFIG_CHANGED_MSG)+1, "");

      // Search for the <service>-config filename - it is handled specially
      UtlString configFile =  mWorkingDirectory +
         OsPathBase::separator + mServiceName +
         CONFIG_SETTINGS_FILE_SUFFIX;
      if (stdinMsg.contains(configFile))
      {
         configDbChanged(file);
      }
      else
      {
         ssize_t filePos = file.first('\'');
         UtlString fileName = file;
         UtlString fileType = file.remove(filePos-1);
         fileName = fileName.remove(0, filePos+1);
         fileName.remove(fileName.last('\'')+1);
         resourceChanged(fileType, fileName);
      }
   }
   else
   {
   }
}

/// destructor
SipXecsService::~SipXecsService()
{
   delete mConfigDb;
   OsSysLog::add(FAC_KERNEL, PRI_NOTICE, "%s >>>>>>>>>>>>>>>> STOPPED",
                 mServiceName.data()
                 );

   // Flush the log file
   OsSysLog::flush();
};
