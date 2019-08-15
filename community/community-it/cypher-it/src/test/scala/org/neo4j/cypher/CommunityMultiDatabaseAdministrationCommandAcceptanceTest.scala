/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher

import java.io.File

import org.neo4j.configuration.Config
import org.neo4j.configuration.GraphDatabaseSettings.{DEFAULT_DATABASE_NAME, SYSTEM_DATABASE_NAME, default_database}
import org.neo4j.cypher.internal.javacompat.GraphDatabaseCypherService
import org.neo4j.dbms.database.{DatabaseContext, DatabaseManager, DefaultSystemGraphInitializer}
import org.neo4j.exceptions.DatabaseAdministrationException
import org.neo4j.graphdb.config.Setting
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge
import org.neo4j.logging.Log
import org.neo4j.server.security.auth.{InMemoryUserRepository, SecureHasher}
import org.neo4j.server.security.systemgraph.{BasicSystemGraphOperations, ContextSwitchingSystemGraphQueryExecutor, UserSecurityGraphInitializer}
import org.scalatest.enablers.Messaging.messagingNatureOfThrowable

import scala.collection.Map

class CommunityMultiDatabaseAdministrationCommandAcceptanceTest extends CommunityAdministrationCommandAcceptanceTestBase {
  private val defaultConfig = Config.defaults()

  test("should fail at startup when config setting for default database name is invalid") {
    // GIVEN
    val startOfError = "Error evaluating value for setting 'dbms.default_database'. "

    // Empty name
    the[IllegalArgumentException] thrownBy {
      // WHEN
      Config.defaults(default_database, "")
      // THEN
    } should have message startOfError + "The provided database name is empty."

    // Starting on invalid character
    the[IllegalArgumentException] thrownBy {
      // WHEN
      Config.defaults(default_database, "_default")
      // THEN
    } should have message startOfError + "Database name '_default' is not starting with an ASCII alphabetic character."

    // Has prefix 'system'
    the[IllegalArgumentException] thrownBy {
      // WHEN
      Config.defaults(default_database, "system-mine")
      // THEN
    } should have message startOfError + "Database name 'system-mine' is invalid, due to the prefix 'system'."

    // Contains invalid characters
    the[IllegalArgumentException] thrownBy {
      // WHEN
      Config.defaults(default_database, "mydbwith_and%")
      // THEN
    } should have message startOfError + "Database name 'mydbwith_and%' contains illegal characters. Use simple ascii characters, numbers, dots and dashes."

    // Too short name
    the[IllegalArgumentException] thrownBy {
      // WHEN
      Config.defaults(default_database, "me")
      // THEN
    } should have message startOfError + "The provided database name must have a length between 3 and 63 characters."

    // Too long name
    val name = "ihaveallooootoflettersclearlymorethenishould-ihaveallooootoflettersclearlymorethenishould"
    the[IllegalArgumentException] thrownBy {
      // WHEN
      Config.defaults(default_database, name)
      // THEN
    } should have message startOfError + "The provided database name must have a length between 3 and 63 characters."
  }

  // SHOW DEFAULT DATABASE tests

  test("should show default database") {
    // GIVEN
    setup( defaultConfig )

    // WHEN
    val result = execute("SHOW DEFAULT DATABASE")

    // THEN
    result.toList should be(List(Map("name" -> DEFAULT_DATABASE_NAME)))
  }

  test("should show custom default database using show default database command") {
    // GIVEN
    val config = Config.defaults()
    config.set(default_database, "foo")
    setup(config)

    // WHEN
    val result = execute("SHOW DEFAULT DATABASE")

    // THEN
    result.toList should be(List(Map("name" -> "foo")))
  }

  test("should show correct default database for switch of default database") {
    // GIVEN
    val config = Config.defaults()
    setup(config)

    // WHEN
    val result = execute("SHOW DEFAULT DATABASE")

    // THEN
    result.toSet should be(Set(Map("name" -> DEFAULT_DATABASE_NAME)))

    // GIVEN
    config.set(default_database, "foo")
    initSystemGraph(config)

    // WHEN
    val result2 = execute("SHOW DEFAULT DATABASE")

    // THEN
    result2.toSet should be(Set(Map("name" -> "foo")))
  }

  test("should fail when showing default database when not on system database") {
    setup(defaultConfig)
    selectDatabase(DEFAULT_DATABASE_NAME)
    the [DatabaseAdministrationException] thrownBy {
      // WHEN
      execute("SHOW DEFAULT DATABASE")
      // THEN
    } should have message
      "This is an administration command and it should be executed against the system database: SHOW DEFAULT DATABASE"
  }

  // Test for non-valid community commands

  test("should fail on showing database from community") {
    setup( defaultConfig )
    assertFailure(s"SHOW DATABASE $DEFAULT_DATABASE_NAME",
      s"Unsupported administration command: SHOW DATABASE $DEFAULT_DATABASE_NAME")
  }

  test("should fail on showing non-existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("SHOW DATABASE foo", "Unsupported administration command: SHOW DATABASE foo")
  }

  test("should fail on showing databases from community") {
    setup( defaultConfig )
    assertFailure("SHOW DATABASES", "Unsupported administration command: SHOW DATABASES")
  }

  test("should fail on creating database from community") {
    setup( defaultConfig )
    assertFailure("CREATE DATABASE foo", "Unsupported administration command: CREATE DATABASE foo")
  }

  test("should fail on creating already existing database with correct error message") {
    setup( defaultConfig )
    assertFailure(s"CREATE DATABASE $DEFAULT_DATABASE_NAME",
      s"Unsupported administration command: CREATE DATABASE $DEFAULT_DATABASE_NAME")
  }

  test("should fail on dropping database from community") {
    setup( defaultConfig )

    assertFailure(s"DROP DATABASE $DEFAULT_DATABASE_NAME",
      s"Unsupported administration command: DROP DATABASE $DEFAULT_DATABASE_NAME")

    assertFailure(s"DROP DATABASE IF EXISTS $DEFAULT_DATABASE_NAME",
      s"Unsupported administration command: DROP DATABASE IF EXISTS $DEFAULT_DATABASE_NAME")
  }

  test("should fail on dropping non-existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("DROP DATABASE foo", "Unsupported administration command: DROP DATABASE foo")
  }

  test("should fail on starting database from community") {
    setup( defaultConfig )
    assertFailure(s"START DATABASE $DEFAULT_DATABASE_NAME",
      s"Unsupported administration command: START DATABASE $DEFAULT_DATABASE_NAME")
  }

  test("should fail on starting non-existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("START DATABASE foo", "Unsupported administration command: START DATABASE foo")
  }

  test("should fail on stopping database from community") {
    setup( defaultConfig )
    assertFailure(s"STOP DATABASE $DEFAULT_DATABASE_NAME",
      s"Unsupported administration command: STOP DATABASE $DEFAULT_DATABASE_NAME")
  }

  test("should fail on stopping non-existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("STOP DATABASE foo", "Unsupported administration command: STOP DATABASE foo")
  }

  // Disable normal database creation because we need different settings on each test
  override protected def initTest() {}

  private def setup(config: Config): Unit = {
    managementService = graphDatabaseFactory(new File("test")).impermanent().setConfig( Config.newBuilder().fromConfig(config).build() ).setInternalLogProvider(logProvider).build()
    graphOps = managementService.database(SYSTEM_DATABASE_NAME)
    graph = new GraphDatabaseCypherService(graphOps)

    initSystemGraph(config)
  }

  private def initSystemGraph(config: Config): Unit = {
    val databaseManager = graph.getDependencyResolver.resolveDependency(classOf[DatabaseManager[DatabaseContext]])
    val queryExecutor: ContextSwitchingSystemGraphQueryExecutor = new ContextSwitchingSystemGraphQueryExecutor(databaseManager, threadToStatementContextBridge())
    val secureHasher: SecureHasher = new SecureHasher
    val systemGraphOperations: BasicSystemGraphOperations = new BasicSystemGraphOperations(queryExecutor, secureHasher)

    val securityGraphInitializer = new UserSecurityGraphInitializer(
      new DefaultSystemGraphInitializer(databaseManager, config),
      queryExecutor,
      mock[Log],
      systemGraphOperations,
      () => new InMemoryUserRepository,
      () => new InMemoryUserRepository,
      secureHasher)

    securityGraphInitializer.initializeSecurityGraph()
    selectDatabase(SYSTEM_DATABASE_NAME)
  }

  private def threadToStatementContextBridge(): ThreadToStatementContextBridge = {
    graph.getDependencyResolver.resolveDependency(classOf[ThreadToStatementContextBridge])
  }

  // Use the default value instead of the new value in CommunityDDLAcceptanceTestBase
  override def databaseConfig(): Map[Setting[_], Object] = Map()
}
