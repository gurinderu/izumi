# distage-framework-docker

@@toc { depth=2 }

### Key Features

- Provides Docker containers as resources
- Reasonable defaults with flexible configuration
- Excellent for providing Docker implementations of services for integration tests

### Dependencies

Add the `distage-framework-docker` library:

@@dependency[sbt] {
  group="io.7mind.izumi"
  artifact="distage-framework-docker_2.13"
  version="$izumi.version$"
}

### Overview

Usage of `distage-framework-docker` generally follows these steps:

1. Create @scaladoc[`ContainerDef`](izumi.distage.docker.ContainerDef)s for the containers the
   application requires
2. Create a module that declares the container component
3. Include the @scaladoc[`DockerSupportModule`](izumi.distage.docker.modules.DockerSupportModule) in
   the application's modules

#### 1. Create a `ContainerDef`

The @scaladoc[`ContainerDef`](izumi.distage.docker.ContainerDef) is a trait used to define a Docker
container. Extend this trait and provide an implementation of the `config` method.

The required parameters for `Config` are:

- `image` - the docker image to use
- `ports` - ports to map on the docker container

See @scaladoc[`Docker.ContainerConfig[Tag]`](izumi.distage.docker.Docker$$ContainerConfig) for
additional parameters.

Example [postgres](https://hub.docker.com/_/postgres/) container definition:

```scala mdoc:to-string
import izumi.distage.docker.{ContainerDef, Docker}

object PostgresDocker extends ContainerDef {
  val primaryPort: Docker.DockerPort = Docker.DockerPort.TCP(5432)

  override def config: Config = {
    Config(
      image = "library/postgres:12.2",
      ports = Seq(primaryPort),
      env = Map("POSTGRES_PASSWORD" -> "postgres"),
    )
  }
}
```

#### 2. Declare Container Components

To use this container, a module that declares this component is required:

Use @scaladoc[`make`](izumi.distage.docker.ContainerDef#make) for binding in a `ModuleDef`:

```scala mdoc:to-string
import distage.ModuleDef
import izumi.reflect.TagK

class PostgresDockerModule[F[_]: TagK] extends ModuleDef {
  make[PostgresDocker.Container].fromResource {
    PostgresDocker.make[F]
  }
}
object PostgresDockerModule {
  def apply[F[_]: TagK] = new PostgresDockerModule[F]
}
```


#### 3. Include the `DockerSupportModule`

Include the `izumi.distage.docker.modules.DockerSupportModule` module in the application
modules. This module contains required component declarations and initializes the
`Docker.ClientConfig`.

```scala mdoc:silent
import cats.effect.IO
import com.typesafe.config.ConfigFactory
import distage.Injector
import distage.config.AppConfigModule
import izumi.distage.docker.modules.DockerSupportModule
import izumi.logstage.distage.LogIOModule
import logstage.LogRouter

object DistageFrameworkModules extends ModuleDef {
  // required for docker
  include(DockerSupportModule[IO])

  // standard distage framework modules
  include(AppConfigModule(ConfigFactory.defaultApplication()))
  include(LogIOModule[IO](LogRouter(), true))
}
```

The required framework modules plus `PostgresDockerModule` is sufficient to depend on Docker
containers:

```scala mdoc:to-string
def minimalExample = {
  val applicationModules = new ModuleDef {
    include(PostgresDockerModule[IO])
    include(DistageFrameworkModules)
  }

  Injector[IO]().produceRun(applicationModules) {
    container: PostgresDocker.Container =>
      val port = container.availablePorts.first(PostgresDocker.primaryPort)
      IO(println(s"postgres is available on port ${port}"))
  }
}

minimalExample.unsafeRunSync()(cats.effect.unsafe.IORuntime.global)
```

If the `DockerSupportModule` is not included in an application then a get of a Docker container
dependent resource will fail with a `izumi.distage.model.exceptions.interpretation.ProvisioningException`.

### Config API

The @scaladoc[`DockerProviderExtensions`](izumi.distage.docker.DockerContainer$$DockerProviderExtensions)
provides additional APIs for modiying the container definition.

#### modifyConfig

Use @scaladoc[`modifyConfig`](izumi.distage.docker.DockerContainer$$DockerProviderExtensions#modifyConfig)
to modify the configuration of a container. The modifier is instantiated to a `Functoid`, which
will summon any additional dependencies.

For example, to change the user of the PostgreSQL container:

```scala mdoc:to-string
class PostgresRunAsAdminModule[F[_]: TagK] extends ModuleDef {
  make[PostgresDocker.Container].fromResource {
    PostgresDocker
      .make[F]
      .modifyConfig {
        () => (old: PostgresDocker.Config) =>
          old.copy(user = Some("admin"))
      }
  }
}
```

Suppose `HostPostgresData` is a component provided by the application modules. This path can be
added to the PostgreSQL container's mounts by adding to the additional dependencies of the provider
magnet:

```scala mdoc:to-string
final case class HostPostgresData(path: String)

class PostgresWithMountsDockerModule[F[_]: TagK] extends ModuleDef {
  make[PostgresDocker.Container].fromResource {
    PostgresDocker.make[F].modifyConfig {
      (hostPostgresData: HostPostgresData) =>
        (old: PostgresDocker.Config) =>
          val dataMount = Docker.Mount(hostPostgresData.path, "/var/lib/postgresql/data")
          old.copy(mounts = old.mounts :+ dataMount)
    }
  }
}
```

#### dependOnContainer

@scaladoc[`dependOnContainer`](izumi.distage.docker.DockerContainer$$DockerProviderExtensions#dependOnContainer)
adds a dependency on a given Docker container.
`distage` ensures the requested container is available before the dependent.

For example, suppose a system under test requires both PostgreSQL and Elasticsearch. One option is to
use `dependOnContainer` to declare the Elasticsearch container depends on the PostgreSQL container:

```scala mdoc:to-string
object ElasticSearchDocker extends ContainerDef {
  val ports = Seq(9200, 9300)

  override def config: Config = {
    Config(
      image = "docker.elastic.co/elasticsearch/elasticsearch:7.7.0",
      ports = ports.map(Docker.DockerPort.TCP(_)),
      env = Map("discovery.type" -> "single-node")
    )
  }
}

class ElasticSearchPlusPostgresModule[F[_]: TagK] extends ModuleDef {
  make[PostgresDocker.Container].fromResource {
    PostgresDocker.make[F]
  }

  make[ElasticSearchDocker.Container].fromResource {
    ElasticSearchDocker.make[F].dependOnContainer(PostgresDocker)
  }
}
```

Another example of dependencies between containers is in the "Docker Container Networks" section later in this document.

### Availability and Health Checks

The `healthCheck` properties of
@scaladoc[Docker.ContainerConfig](izumi.distage.docker.Docker$$ContainerConfig) configure the
health checks. A container resource will not be provided if the health checks did not succeed at
acquire time. These are used to determine if an existing container can be used and if starting a
fresh container succeeded.

By default, the `healthCheck` is a port check of the ports from the container config. There are
several standard checks provided in
@scaladoc[ContainerHealthCheck](izumi.distage.docker.healthcheck.ContainerHealthCheck$) that can be
combined to cover many common cases.

The @scaladoc[availablePorts](izumi.distage.docker.DockerContainer#availablePorts) property of
the container resource are the mapped ports that passed the health check. This is a map
from a `Docker.DockerPort` provided in the config to a host and port. For example:

```scala mdoc:invisible
def _ref = (_: ElasticSearchDocker.Container).availablePorts.first(izumi.distage.docker.Docker.DockerPort.TCP(9020))
```

```scala
val port = container.availablePorts.first(PostgresDocker.primaryPort)
```

would be a host and port mapped to the Postgres container's primary port that have passed the
health check.

### Usage in Integration Tests

A common use case is using Docker containers to provide service implementations for integration test,
such as using a PostgreSQL container for verifying an application that uses a PostgreSQL database.

Consider the example application below. This application is written to depend on a
[doobie](https://tpolecat.github.io/doobie/) `Transactor`, which is constructed from a
`PostgresServerConfig`.

```scala mdoc:silent
import cats.effect.Async
import doobie.Transactor
import doobie.syntax.connectionio._
import doobie.syntax.string._

final class PostgresExampleApp(
  xa: Transactor[IO]
) {
  def plusOne(a: Int): IO[Int] = {
    sql"select ${a} + 1".query[Int].unique.transact(xa)
  }

  val run: IO[Unit] = {
    for {
      v <- plusOne(1)
      _ <- IO(println(s"1 + 1 = ${v}."))
    } yield ()
  }
}

// the postgres configuration used to construct the Transactor
final case class PostgresServerConfig(
  host: String,
  port: Int,
  database: String,
  username: String,
  password: String,
)

object TransactorFromConfigModule extends ModuleDef {
  make[Transactor[IO]].from {
    (config: PostgresServerConfig, async: Async[IO]) =>

      Transactor.fromDriverManager[IO](
        driver = "org.postgresql.Driver",
        url    = s"jdbc:postgresql://${config.host}:${config.port}/${config.database}",
        user   = config.username,
        pass   = config.password,
      )(async)
  }
}
```

Note that the above code is agnostic of environment.
Provided a `PostgresServerConfig`, the `Transactor` needed by `PostgresExampleApp` can be constructed.

An integration test would use a module that provides the `PostgresServerConfig` from a `PostgresDocker.Container`:

```scala mdoc:to-string
object PostgresUsingDockerModule extends ModuleDef {
  make[PostgresServerConfig].from {
    container: PostgresDocker.Container => {
      val knownAddress = container.availablePorts.first(PostgresDocker.primaryPort)
      PostgresServerConfig(
        host     = knownAddress.hostString,
        port     = knownAddress.port,
        database = "postgres",
        username = "postgres",
        password = "postgres",
      )
    }
  }

  make[PostgresDocker.Container].fromResource {
    PostgresDocker.make[IO]
  }
}
```

Using `distage-testkit` the test would be written like this:

```scala mdoc:silent
import izumi.distage.testkit.scalatest.{AssertCIO, Spec1}
import distage.DIKey

class PostgresExampleAppIntegrationTest extends Spec1[IO] with AssertCIO {
  override def config = super.config.copy(
    moduleOverrides = new ModuleDef {
      include(TransactorFromConfigModule)
      include(PostgresUsingDockerModule)
      include(DistageFrameworkModules)
      make[PostgresExampleApp]
    },
    memoizationRoots = Set(
      DIKey[PostgresServerConfig]
    ),
  )

  "distage docker" should {

    "support integration tests using containers" in {
      app: PostgresExampleApp =>
        for {
          v <- app.plusOne(1)
          _ <- assertIO(v == 2)
        } yield ()
    }

  }
}
```

Typically, this would be run by the test runner. For completeness, the example can be run directly
using:

```scala mdoc:to-string
def postgresDockerIntegrationExample = {
  val applicationModules = new ModuleDef {
    include(TransactorFromConfigModule)
    include(PostgresUsingDockerModule)
    include(DistageFrameworkModules)

    make[PostgresExampleApp]
  }

  Injector[IO]().produceRun(applicationModules) {
    app: PostgresExampleApp =>
      app.run
  }
}

postgresDockerIntegrationExample.unsafeRunSync()(cats.effect.unsafe.IORuntime.global)
```

### Docker Container Environment

The container config (@scaladoc[Docker.ContainerConfig](izumi.distage.docker.Docker$$ContainerConfig))
defines the container environment. Of note are the environment variables, command of entrypoint, and working
directory properties:

- `env: Map[String, String]` - environment variables to setup in container
- `cmd: Seq[String]` - command of entrypoint
- `cwd: Option[String]` - working directory in container

Once defined in a `ContainerDef` the config may be modified by distage modules. See `modifyConfig`
above for one mechanism.

The container ports will also add to the configured environment variables:

- `DISTAGE_PORT_<protocol>_<originalPort>` are the host ports allocated for the container


### Docker Metadata

The host ports allocated for the container are also added to the container metadata as labels.
These labels follow the pattern `distage.port.<protocol>.<originalPort>`. The value is the integer port
number.

### Docker Container Networks

`distage-framework-docker` can automatically manage [Docker
networks](https://docs.docker.com/engine/reference/commandline/network/).

To connect containers to the same Docker network, use a
@scaladoc[`ContainerNetworkDef`](izumi.distage.docker.ContainerNetworkDef):

1. Create a `ContainerNetworkDef`.
2. Add the network to each container's config.

This will ensure the containers are all connected to the network. Assuming no reuse, distage will
create the required network and add each container to that network.

#### 1. Create a `ContainerNetworkDef`

A minimal @scaladoc[`ContainerNetworkDef`](izumi.distage.docker.ContainerNetworkDef) uses the default
configuration.

```mdoc:to-string
object TestClusterNetwork extends ContainerNetworkDef {
  override def config: Config = Config()
}
```

By default this object identifies the network. The associated tag type uniquely identifies this
network within the application. In addition, any created network will have a label with this name in
Docker.

#### 2. Add to Container Config

A container will be connected to all networks in the `networks` of the `config`. The method
@scaladoc[`connectToNetwork`](izumi.distage.docker.DockerContainer$$DockerProviderExtensions#connectToNetwork)
adds a dependency on a network defined by a `ContainerNetworkDef`, as in this example:

```mdoc:to-string
class TestClusterNetworkModule[F[_]: TagK] extends ModuleDef {
  make[TestClusterNetwork.Network].fromResource {
    TestClusterNetwork.make[F]
  }
  make[PostgresDocker.Container].fromResource {
    PostgresDocker.make[F].connectToNetwork(TestClusterNetwork)
  }
  make[ElasticSearchDocker.Container].fromResource {
    ElasticSearchDocker.make[F].dependOnContainer(PostgresDocker).connectToNetwork(TestClusterNetwork)
  }
}
```

The use of `connectToNetwork` automatically adds a dependency on `TestClusterNetwork.Network` to each
container.

#### Container Network Reuse

Container networks, like containers, are reused by default. If there is an existing network that
matches a definition then that network will be used. This can be disabled by setting the `reuse`
configuration to `DockerReusePolicy.KillOnExitNoReuse`:

```mdoc:to-string
object AlwaysFreshNetwork extends ContainerNetworkDef {
  override def config: Config = Config(reuse = Docker.DockerReusePolicy.KillOnExitNoReuse)
}
```

For an existing network to be reused, the config and object name at time of creation must be the match the current configuration value and object.

### Docker Client Configuration

The @scaladoc[`Docker.ClientConfig`](izumi.distage.docker.Docker$$ClientConfig) is the configuration
of the Docker client used. Including the module `DockerSupportModule` will provide a
`Docker.ClientConfig`.

There are two primary mechanisms to change the `Docker.ClientConfig` provided by
`DockerSupportModule`:

1. Provide a `docker` configuration in the `application.conf`:

```hocon
# include the default configuration
include "docker-reference.conf"

# override docker object fields
docker {
  globalReuse = "always-kill"
}
```

2. Override the `DockerSupportModule` using `overriddenBy`:

```scala mdoc:to-string
import izumi.distage.docker.Docker
import izumi.distage.docker.Docker.DockerReusePolicy

class CustomDockerConfigExampleModule[F[_]: TagK] extends ModuleDef {
  include(DockerSupportModule[F] overriddenBy new ModuleDef {
    make[Docker.ClientConfig].from {
      Docker.ClientConfig(
        globalReuse       = DockerReusePolicy.ReuseEnabled,
        useRemote         = true,
        useGlobalRegistry = true,
        remote            = Some(
          Docker.RemoteDockerConfig(
            host      = "tcp://localhost:2376",
            tlsVerify = true,
            certPath  = "/home/user/.docker/certs",
            config    = "/home/user/.docker",
          )
        ),
        globalRegistry = Some("index.docker.io"), // docker hub default registry
        registryConfigs = List(
            Docker.DockerRegistryConfig(
              registry = "index.docker.io",
              username = "docker_user",
              password = "i_love_docker",
              email    = Some("dockeruser@github.com"),
            ),
            Docker.DockerRegistryConfig(
              registry = "your.registry",
              username = "my_registry_user",
              password = "i_love_my_registry",
            ),
        ),
      )
    }
  })
}
```

### Container Reuse

By default acquiring a container resource does not always start a fresh container. Likewise on
releasing the resource the container will not be destroyed.  When a container resource is acquired,
the Docker system is inspected to determine if a matching container is already executing. If a
matching container is found this container is referenced by the
`ContainerResource`. Otherwise a fresh container is started.  In both cases the acquired
`ContainerResource` will have passed configured health checks.

#### Matching Containers for Reuse

For an existing container to be reused, all the following must be true:

- The current client config has `globalReuse == DockerReusePolicy.ReuseEnabled`
- The container config has `reuse == DockerReusePolicy.ReuseEnabled`
- The running container was created for reuse.
- The running container uses the same image as the container config.
- All ports requested in the container config must be mapped for the running container.

#### Configuring Reuse

`DockerReusePolicy` has 2 possible values:

- `ReuseEnabled`: the resource (container or network) will be always freed after use and existing matching containers will not be reused
- `ReuseDisabled`: the resource will be always be freed after use but existing matching containers will be reused

The `ContainerDef.Config.reuse` should be `DockerReusePolicy.ReuseDisabled` to disable reuse for a specific container.  While
`Docker.ClientConfig.globalReuse` should be `DockerReusePolicy.ReuseDisabled` to disable reuse throughout the application.


#### Improving Reuse Performance

When utilizing reuse, the performance cost of inspecting the Docker system can be avoided using memoization roots. For
example, in this integration test the container resource is not reconstructed for each test. Because the
resource is not reconstructed there is no repeated inspection of the Docker system.

```scala mdoc:to-string
class NoReuseByMemoizationExampleTest extends Spec1[IO] {
  override def config = super.config.copy(
    moduleOverrides = new ModuleDef {
      include(DistageFrameworkModules)
      include(PostgresDockerModule[IO])
    },
    memoizationRoots = Set(
      DIKey[PostgresDocker.Container]
    )
  )

  "distage docker" should {
    "provide a fresh container resource" in { c: PostgresDocker.Container =>
      val port = c.availablePorts.first(PostgresDocker.primaryPort)
      IO(println(s"port ${port}"))
    }

    "provide the same resource" in { c: PostgresDocker.Container =>
      val port = c.availablePorts.first(PostgresDocker.primaryPort)
      IO(println(s"port ${port}"))
    }
  }
}
```


### Examples

The `distage-example` project uses `distage-framework-docker` to provide a
[PostgresDockerModule](https://github.com/7mind/distage-example/blob/develop/src/test/scala/leaderboard/PostgresDockerModule.scala).

The `distage-framework-docker` project contains example `ContainerDef`s and modules for various
services under
[`izumi.distage.docker.examples`](https://github.com/7mind/izumi/tree/develop/distage/distage-framework-docker/src/main/scala/izumi/distage/docker/examples)
namespace.

### Tips

To kill all containers spawned by `distage`, use the following command:

```shell
docker rm -f $(docker ps -q -a -f 'label=distage.type')
```

### Troubleshooting

```
// izumi.distage.model.exceptions.interpretation.ProvisioningException: Provisioner stopped after 1 instances, 2/14 operations failed:
//  - {type.izumi.distage.docker.DockerClientWrapper[=λ %0 → IO[+0]]} (distage-framework-docker.md:40), MissingInstanceException: Instance is not available in the object graph: {type.izumi.distage.docker.DockerClientWrapper[=λ %0 → IO[+0]]}.
```

This error means that @scaladoc[`DockerSupportModule`](izumi.distage.docker.modules.DockerSupportModule) hasn't been `include`d with the application modules.

`DockerClientWrapper` component is provided by @scaladoc[`izumi.distage.docker.modules.DockerSupportModule`](izumi.distage.docker.modules.DockerSupportModule).

### References

- Introduced in [release 0.9.13](https://github.com/7mind/izumi/releases/tag/v0.9.13)
- An [example PR](https://github.com/7mind/distage-livecode/pull/2/files) showing how to use them.
- The `distage-example` [PostgresDockerModule](https://github.com/7mind/distage-example/blob/develop/src/test/scala/leaderboard/PostgresDockerModule.scala).
