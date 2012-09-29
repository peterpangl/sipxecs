#
# Copyright (C) 2007 Pingtel Corp., certain elements licensed under a Contributor Agreement.
# Contributors retain copyright to elements licensed under a Contributor Agreement.
# Licensed to the User under the LGPL license.
#
##############################################################################

require 'tempfile'

require 'utils/call_resolver_configure'
require 'utils/configure'
require 'db/database_url'
require 'utils/exceptions'
require 'utils/utils'

# Attempts to open stunnel connections to all configured distributed machines.
class StunnelConnection

  attr_reader :log

  STUNNEL_EXEC = '/usr/sbin/stunnel'

  def initialize(config)
    @config = config
    @log = config.log
    @pid = nil
  end

  def open
    # Open stunnel connection only if HA is enabled
    return unless @config.ha?

    config_file = Tempfile.new('stunnel-config')
    generate_stunnel_config_file(config_file)
    config_file.close

    log.info("Starting #{STUNNEL_EXEC} with configuration: #{config_file.path}")
    @pid = fork do
      exec(STUNNEL_EXEC, config_file.path)
    end
    # give it a chance to start
    sleep(3)
    log.info("Stunnel started: #{@pid}")
  end

  def close
    return unless @pid
    Process.kill("TERM", @pid)
    Process.wait(@pid, Process::WNOHANG)
    log.info {"Stunnel terminated. Exit status: <#{$?.exitstatus}>"}
  end

  # Generate the stunnel configuration based on the call resolver configuration
  def generate_stunnel_config_file(config_file)
    ca_path = File.join(@config.ssldir, 'authorities')
    cert_file = File.join(@config.ssldir, 'ssl.crt')
    key_file = File.join(@config.ssldir, 'ssl.key')
    log_file = File.join(@config.logdir, 'sipxstunnel.log')
    pid_file = File.join(@config.logdir, 'sipxstunnel.pid')

    header = %Q/# This file was automatically generated by sipxcallresolver
client = yes
foreground = yes
CApath = #{ca_path}
cert = #{cert_file}
key = #{key_file}
verify = 2
debug = #{@config.stunnel_debug}
output = #{log_file}
pid = #{pid_file}
fips = no

[Postgres-1]
accept = #{@config.cse_hosts[1].port}
/

    config_file << header
    if log.debug?
      log.debug(header)
    end

    @config.cse_hosts.each_with_index do |cse_host, i|
      next if cse_host.local
      host_def = %Q/connect = #{cse_host.host}:#{@config.cse_connect_port}
/
      config_file << host_def
      if log.debug?
        log.debug(host_def)
      end
    end
  end

  def raise_exception(err_msg, klass = CallResolverException)
    Utils.raise_exception(err_msg, klass)
  end
end