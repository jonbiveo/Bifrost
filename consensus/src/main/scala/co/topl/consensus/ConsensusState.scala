package co.topl.consensus

import co.topl.models.utility.Ratio
import co.topl.models.{Box, TaktikosAddress}

trait ConsensusState[F[_]] {
  def nEpochState: F[ConsensusEpochState[F]]
  def nMinus1EpochState: F[ConsensusEpochState[F]]
  def nMinus2EpochState: F[ConsensusEpochState[F]]
}

trait ConsensusEpochState[F[_]] {
  def registrationOf(address:  TaktikosAddress): F[Option[Box.Values.TaktikosRegistration]]
  def relativeStakeOf(address: TaktikosAddress): F[Option[Ratio]]
}
