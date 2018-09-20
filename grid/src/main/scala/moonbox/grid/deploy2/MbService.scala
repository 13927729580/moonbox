/*-
 * <<
 * Moonbox
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package moonbox.grid.deploy2

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import moonbox.common.message._
import moonbox.common.{MbConf, MbLogging}
import moonbox.core.CatalogContext
import moonbox.grid.api.{ClosedSession, _}
import moonbox.grid.deploy2.authenticate.LoginManager
import moonbox.grid.deploy2.rest.TokenManager

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class MbService(conf: MbConf, catalogContext: CatalogContext, proxy: ActorRef) extends MbLogging {

	implicit val timeout = Timeout(new FiniteDuration(3600 * 24, SECONDS))
// TODO
	private val loginManager = new LoginManager(catalogContext, new TokenManager(conf), this)

	def getLoginManager(): LoginManager = loginManager

	def login(username: String, password: String): Future[Option[String]] = {
		Future(loginManager.login(username, password))
	}

	def isLogin(token: String): Future[Option[String]] = {
		Future(loginManager.isLogin(token))
	}

	def logout(token: String): Future[LogoutOutbound] = {
		Future(loginManager.logout(token)).map { res =>
			LogoutOutbound(message = Some("Logout successfully."))
		}
	}

	def openSession(token: String, database: Option[String]): Future[OpenSessionOutbound] = {
		isLogin(token).flatMap {
			case Some(username) =>
				askForCompute(OpenSession(username, database)).mapTo[OpenSessionResponse].flatMap {
					case OpenedSession(sessionId) =>
						loginManager.putSession(token, sessionId)
						Future(OpenSessionOutbound(Some(sessionId), None))
					case OpenSessionFailed(error) =>
						Future(OpenSessionOutbound(None, Some(error)))
				}
			case None =>
				Future(OpenSessionOutbound(None, Some("Please login first.")))
		}
	}

	def closeSession(token: String, sessionId: String): Future[CloseSessionOutbound] = {
		isLogin(token).flatMap {
			case Some(username) =>
				askForCompute(CloseSession(sessionId)).mapTo[CloseSessionResponse].flatMap {
					case ClosedSession =>
						loginManager.removeSession(token)
						Future(CloseSessionOutbound(None))
					case CloseSessionFailed(error) => Future(CloseSessionOutbound(Some(error)))
				}
			case None =>
				Future(CloseSessionOutbound(Some("Please login first.")))
		}
	}

	def jobQuery(token: String, sessionId: String, sqls: Seq[String], fetchSize: Int = 200): Future[QueryOutbound] = {
		isLogin(token).flatMap {
			case Some(username) =>
				askForCompute(JobQuery(sessionId, username, sqls)).mapTo[JobResultResponse].flatMap {
					case JobFailed(jobId, error) =>
						Future(QueryOutbound(Some(jobId), error = Some(error)))
					case JobCompleteWithExternalData(jobId, message) =>
						Future(QueryOutbound(Some(jobId)))
					case JobCompleteWithDirectData(jobId, data) =>
						Future(QueryOutbound(Some(jobId), data = Some(data)))
				}
			case None =>
				Future(QueryOutbound(None, error = Some("Please login first.")))
		}
	}


	def jobSubmitAsync(token: String, sqls: Seq[String]): Future[SubmitOutbound] = {
		isLogin(token).flatMap {
			case Some(username) =>
				askForCompute(JobSubmit(username, sqls, async = true)).mapTo[JobHandleResponse].map {
					case JobAccepted(jobId) =>
						SubmitOutbound(jobId = Some(jobId))
					case JobRejected(error) =>
						SubmitOutbound(error = Some(error))
				}
			case None =>
				Future(SubmitOutbound(error = Some("Please login first.")))
		}
	}

	def jobCancel(token: String, jobId: String): Future[CancelOutbound] = {
		isLogin(token).flatMap {
			case Some(username) =>
				askForCompute(JobCancel(jobId)).mapTo[JobCancelResponse].map {
					case JobCancelSuccess(id) =>
						CancelOutbound(jobId = jobId)
					case JobCancelFailed(id, error) =>
						CancelOutbound(jobId = jobId, error = Some(error))
				}
			case None =>
				Future(CancelOutbound(jobId = jobId, error = Some("Please login first.")))
		}
	}

	def jobProgress(token: String, jobId: String): Future[ProgressOutbound] = {
		isLogin(token).flatMap {
			case Some(username) =>
				askForCompute(JobProgress(jobId)).mapTo[JobProgressResponse].map {
					case JobProgressState(id, jobInfo) =>
						ProgressOutbound(id, error = jobInfo.errorMessage, status = Some(s"${jobInfo.status}"))
				}
			case None =>
				Future(ProgressOutbound(jobId = jobId, error = Some("Please login first.")))
		}
	}

	private def askForCompute(message: MbApi): Future[MbApi] = askFor(proxy)(message)


	private def askFor(actorRef: ActorRef)(message: MbApi): Future[MbApi] = {
		(actorRef ask message).mapTo[MbApi]
	}


}
